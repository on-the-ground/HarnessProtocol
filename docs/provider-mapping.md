# Provider mapping

조사 기준일은 2026-09-03이다. Codex는 공식 [SDK 문서](https://developers.openai.com/codex/sdk)와 [App Server 문서](https://developers.openai.com/codex/app-server), 그리고 저장소에 고정된 `openai-codex==0.147.0` 설치본을, Gemini CLI는 공식 저장소의 [SDK 설계](https://github.com/google-gemini/gemini-cli/blob/main/packages/sdk/SDK_DESIGN.md)와 [SDK 구현](https://github.com/google-gemini/gemini-cli/tree/main/packages/sdk/src)을 기준으로 삼았다.

## Codex host의 SDK 경계

Codex host(`bridges/codex_sdk_bridge.py`)는 고수준 `Codex`/`AsyncCodex`가 아니라 **`openai_codex.client.CodexClient`** 를 직접 사용한다. 이유는 [spike 8a](spikes/2026-09-03-codex-low-level-client.md)에 있다.

- 고수준 API는 `approval_mode` 기본값을 `auto_review`로 강제하고 approval handler를 주입할 수 없다.
- `CodexClient`는 공개 모듈이지만 `__all__`에 재수출되지 않는다. 그래서 `requirements-codex.txt`의 버전 고정은 계약의 일부다.
- host는 App Server wire vocabulary(`approvalPolicy`, `approvalsReviewer`, `sandbox`, `developerInstructions`, `cwd`, `model`, `config`)를 직접 만든다.

## 공통 목적 대응

| 공통 목적 | Kotlin port | Codex 구현 | Gemini CLI 구현 |
|---|---|---|---|
| 실행 환경 식별 | `AgentHarness.provider` | `codex` | `gemini-cli` |
| 구성 의미 검증 | `validate(AgentSpec)` | workspace-write 외 sandbox의 network 의도 거절 | SDK가 노출하지 않는 모든 policy 거절 |
| 새 대화 시작 | `createSession` | `thread/start` | `GeminiCliAgent.session` + `initialize` |
| 기존 대화 계속 | `resumeSession` | `thread/resume` (응답의 thread id 사용) | `GeminiCliAgent.resumeSession` + `initialize` (응답의 session id 사용) |
| session 해제 | `AgentSession.release` | host `release_session`: 진행 중 turn interrupt, 항목 제거 | host `release_session`: abort, 항목 제거 |
| 지속 문맥 | `AgentSession` | Codex thread id | Gemini CLI session id |
| 완결된 agent loop | `execute` | `turn/start` + turn notification queue | `session.sendStream` |
| 사용자 지시 | `AgentInput.Text` | text input item | `sendStream(prompt)` |
| system/developer 지시 | `AgentSpec.instructions` | `developerInstructions` (`null`이면 생략) | agent `instructions` (`null`이면 생략) |
| 모델 선택 | `AgentSpec.model` | thread model | agent model |
| 작업 위치 | `AgentSpec.workingDirectory` | thread cwd | agent cwd |
| skill 사용 | `AgentSpec.skills` | `$name` mention + skill input item (activation envelope) | `skillDir`로 로드 + `$name` mention |
| 진행 텍스트 | `MessageDelta` | agent-message delta | content event |
| 최종 응답 | `MessageCompleted`, `AgentResult` | `final_answer` phase의 completed agent message; 없으면 delta 누적 | 누적 content + execution completion |
| 추론 과정 관찰 | `ReasoningDelta` | reasoning summary/text delta | thought event |
| 도구 작업 관찰 | `ToolCallChanged` | MCP/dynamic tool item lifecycle | tool-call request/response lifecycle |
| 외부 효과 관찰 | `EffectChanged` | command/file/search item lifecycle | tool-call lifecycle에서 공통 효과로 번역 가능한 항목 |
| 문맥 관리 관찰 | `ContextManaged` | context-compaction item | chat-compressed event |
| 사용량 관찰 | `UsageChanged`, `AgentResult.usage/sessionUsage` | `thread/tokenUsage/updated`: `total`(session) − baseline = execution | `finished.usageMetadata`를 field별 합산 = execution; session 없음 |
| caller 승인 | `CALLER_DECIDES`, `InteractionRequested`, `respond` | host approval handler → `interaction_requested` / `respond_interaction` | SDK approval wiring 없음 → validate 거절 |
| 중단 | `AgentExecution.cancel` | 열린 approval을 `cancel`로 닫고 `turn/interrupt` | `AbortController.abort` |
| 성공/실패/취소 종결 | terminal `AgentEvent` | turn completion/error/status | finished/error/cancel/synthetic terminal event |
| 종료 사유 | `AgentResult.stopReason` | 항상 `FINISHED` (Codex가 step 한도 상태를 노출하면 추가) | `max_session_turns`→`TURN_LIMIT`, `loop_detected`→`LOOP_DETECTED`, `agent_execution_stopped`→`PROVIDER_STOPPED` |
| 알 수 없는 vendor 이벤트 보존 | `ProviderEventObserved` | 원본 method/payload | 원본 type/value |

## 정책 의미

| 정책 | Codex host | Gemini CLI SDK adapter |
|---|---|---|
| filesystem read-only/workspace-write/full-access | `sandbox`: `read-only` / `workspace-write` / `danger-full-access` | 공개 SDK option 부재, 명시적 거절 |
| network deny/allow | `config.sandbox_workspace_write.network_access`. **workspace-write에서만** 적용되므로 다른 sandbox와의 조합은 validate가 거절 | 공개 SDK option 부재, 명시적 거절 |
| approval `PROVIDER_DEFAULT` | `approvalPolicy`/`approvalsReviewer` **생략** → 사용자의 Codex 설정(`config.toml`)이 적용됨 | SDK minimal config (tool policy ALLOW) |
| approval `DENY_ALL` | `approvalPolicy: never` | 거절 |
| approval `AGENT_REVIEWED` | `approvalPolicy: on-request` + `approvalsReviewer: auto_review` | 거절 |
| approval `CALLER_DECIDES` | `approvalPolicy: on-request`, host handler가 caller에게 전달 | 거절 |

### Codex host의 approval handler

`CodexClient`의 기본 handler는 command/file approval에 **무조건 `accept`** 를 돌려준다. host는 어떤 정책에서도 자체 handler를 등록하고 다음을 따른다.

| policy | request 도착 시 |
|---|---|
| `DENY_ALL` | `decline` |
| `AGENT_REVIEWED` | 정상이면 도착하지 않는다. 도착하면 `decline` + `Warning(CONFIGURATION)` |
| `PROVIDER_DEFAULT` | `decline` + `Warning(CONFIGURATION)`. 승인 흐름을 원하면 `CALLER_DECIDES`를 명시한다 |
| `CALLER_DECIDES` | `interaction_requested` → caller 응답 |

handler는 SDK reader thread에서 동기 호출된다. 그래서 cancel/release/close는 **먼저 pending decision queue에 `cancel`을 넣어 handler를 풀고, handler가 결정을 반환했음을 확인한 뒤** `turn/interrupt`나 close를 호출한다. 결정 응답의 write는 SDK reader thread가 하므로 interrupt와의 wire 순서는 best-effort지만, deadlock은 구조적으로 배제된다. `bridges/tests/test_codex_bridge.py`가 stub App Server로 검증한다.

`availableDecisions`는 App Server 문서의 `accept`, `acceptForSession`, `decline`, `cancel`을 그대로 노출한다. pinned SDK의 generated 타입에는 approval request/response 모델이 없어 실제 App Server fixture로 재확인이 필요하다.

## 실패 분류 (`FailureKind`)

| Codex `codexErrorInfo` | kind |
|---|---|
| `contextWindowExceeded` | `CONTEXT_OVERFLOW` |
| `sessionBudgetExceeded`, `usageLimitExceeded` | `BUDGET_EXCEEDED` |
| `serverOverloaded`, `internalServerError` | `TRANSIENT` |
| `httpConnectionFailed`, `responseStreamDisconnected`, `responseStreamConnectionFailed`, `responseTooManyFailedAttempts` | HTTP 401/403이면 `AUTHENTICATION`, 그 외 `TRANSIENT` |
| `unauthorized` | `AUTHENTICATION` |
| `cyberPolicy`, `sandboxError` | `POLICY_BLOCKED` |
| `badRequest`, `threadRollbackFailed`, `activeTurnNotSteerable`, `other`, 없음 | `PROVIDER` |
| 알 수 없는 값 | `UNKNOWN` |
| `error` with `willRetry: true` | terminal 아님 → `Warning(RECOVERABLE)` |

| Gemini 이벤트 | kind |
|---|---|
| `agent_execution_blocked` | `POLICY_BLOCKED` |
| `invalid_stream` | `PROVIDER` |
| `error` 직전에 `context_window_will_overflow` 또는 메시지에 context window 언급 | `CONTEXT_OVERFLOW` |
| 그 외 `error`, `execution_failed` | `PROVIDER` |

host process 종료·stream 실패는 provider와 무관하게 `TRANSPORT`다.

## 의도적으로 capability로 남긴 것

| 기능 | 이유 |
|---|---|
| host-defined custom tool 등록 | embedding 확장이며 core lifecycle이 아니다. |
| active execution steering | Codex에는 있으나 Gemini SDK의 동일 의미 계약이 없다. |
| fork/archive/list/name/goal/review | Codex의 conversation 관리 기능이며 공통 agent 실행 목적은 아니다. |
| structured output 및 multimodal input | Codex SDK는 지원하지만 현재 Gemini CLI SDK `sendStream` 입력은 string이다. |
| 사용자 질문 interaction | 0.147.0 generated 타입에 `requestUserInput`이 없고 Gemini SDK도 노출하지 않는다. |
| caller-selected context token ceiling | 어느 쪽도 보장하지 않는다. |

## SDK 배포 상태

- Codex host는 `openai-codex==0.147.0`을 고정했다. 패키지가 Codex 바이너리(`openai-codex-cli-bin`)를 포함한다.
- Gemini CLI SDK는 공식 monorepo `packages/sdk`에 구현되어 있지만 조사 시점에 `@google/gemini-cli-sdk`가 npm registry에 공개되어 있지 않았다. 그래서 host는 `GEMINI_CLI_SDK_MODULE`로 빌드 산출물 경로를 받는다. `GeminiCliAgent`가 `instructions`를 필수로 요구하는지는 SDK build로 확인하지 못했다. 필수라면 `instructions = null`에 대해 validate ERROR를 추가한다.

Python host를 Kotlin Multiplatform App Server host인 `codex-agent`로 교체하는 안은 현재 보류 상태다. 검토 기준, 차단 조건, 재검토 트리거와 승인 기준은 [codex-agent-adoption-review.md](codex-agent-adoption-review.md)에서 관리한다.

이 상태 차이는 port의 의미를 바꾸지 않는다. 배포가 안정화되면 host의 module resolution만 단순화하면 된다.
