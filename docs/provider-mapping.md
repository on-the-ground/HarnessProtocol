# Provider mapping

이 문서는 [개정 계약](protocol-reference.md)의 목적을 Codex·Gemini CLI·Koog의 서로 다른 수단에 대응시키고 구현 전환 상태를 기록한다. SDK 기능이 공통 목적을 결정하지 않는다.

현재 Codex/Gemini 코드는 이전 공개 API를 사용하고 Koog는 격리 실험이다. 아래의 native 대응은 새 계약 적합성 인증이 아니다. 기존 SDK 조사는 2026-09-03, Koog 실행 근거는 2026-09-04 기준이며 이번 문서 개편에서 외부 release를 재조사하지 않았다.

## 목적별 대응과 전환

| 공통 목적 | Codex의 현재 수단 | Gemini CLI의 현재 수단 | Koog 실험 / 필요한 전환 |
|---|---|---|---|
| AgentHarness | Python CodexClient와 App Server 연결 | Node SDK host | Kotlin graph runtime 직접 연결. bridge 공통화 불필요 |
| Session 문맥 | thread ID | SDK session ID | 완료 이력을 adapter가 보관하면 후속 입력 연결 가능. 기본 session과 영속성 분리 |
| 영속 보관·reopen | thread/resume | resumeSession + initialize | 실험 파일 저장소. 보관 범위·오류·재생성 조건을 선택 계약으로 검증 |
| Task 시작 | turn/start와 notification | sendStream | graph run. 여러 LLM/tool 호출을 하나의 업무 위임으로 묶음 |
| Caller 승인 | handler → pending decision → 응답 | 현 adapter는 CALLER_DECIDES 거절 | tool 실행 전 승인 gate. 실제 효과와 승인 순서 검증 |
| 질문·답변 | 기존 공개 AHP 경로 없음 | 기존 공개 AHP 경로 없음 | native callback은 가능. 새 Question/Answer 전달 계약 필요 |
| 취소 요청 | pending handler 해제 후 turn/interrupt | AbortController.abort | coroutine 취소. 비협조적 도구의 실제 종료 여부는 별도 확인 |
| Outcome | 현재 turn completion/error를 이전 결과·예외로 변환 | finished/error 및 host 알림 | 결과·예외 관찰. 세 경로 모두 새 TaskOutcome/Unresolved 의미로 개정 |
| 산출물 | final-answer 메시지, 부족하면 관찰한 delta | 누적 content | String 결과. TaskOutput과 schema 보장 분리 필요 |
| Tool/effect | tool·command·file·search item lifecycle | tool request/response | 실제 registry 호출 hooks. 내부 node는 자동으로 tool이 되지 않음 |
| Usage/context | input/cache-hit/cache-write/output/reasoning token usage, compaction 알림 | usageMetadata, chat-compressed | 메타데이터가 없으면 unknown. 전체 측정·compaction 미검증 |
| 진단 | 원본 method/payload | 원본 type/value | hooks/internal 객체. ProviderDiagnostic 선택 경로로 분리 |

같은 purpose를 서로 다른 수단으로 이행할 수 있다. provider thread를 그대로 노출하거나 Koog graph에 맞춰 Port를 바꾸지 않는다. TaskId·SessionId·WorkId의 외부 의미는 native ID 형식과 분리한다.

## 현재 Codex 경로의 구현 정보

Host는 고수준 Codex/AsyncCodex가 아닌 `openai_codex.client.CodexClient`를 사용하며 requirements는 0.147.0을 고정한다. 근거는 [client 조사](spikes/2026-09-03-codex-low-level-client.md)다.

- thread/start·resume의 developerInstructions, model, cwd와 정책 wire 필드를 구성한다.
- PROVIDER_DEFAULT는 approval 필드를 생략한다. SDK convenience default로 의미를 바꾸지 않는다.
- CALLER_DECIDES는 caller 응답을 기다린다. reader thread를 막는 handler의 pending 대기를 먼저 풀고 interrupt/close한다.
- 현재 handler는 DENY_ALL에서 decline하며, PROVIDER_DEFAULT/AGENT_REVIEWED 경로에 예상 밖 요청이 오면 decline과 경고를 사용한다.
- APPROVE_FOR_SESSION 같은 선택의 지원 여부는 실제 요청이 제시한 결정과 scope로 검증한다. 일반적인 세션 권한 확대를 추론하지 않는다.

실제 App Server 승인 payload와 결정 문자열 fixture는 기존 검증에서 미확보였다. stub server 통과를 실제 provider 검증으로 바꾸어 서술하지 않는다. 제3자 Kotlin 기반 교체는 [별도 검토](codex-agent-adoption-review.md)의 대상이다.

## 현재 정책·자원 제약

아래는 기존 구현의 지원 상태다. 새 core의 보편적 필드 목록이 아니다.

| 요구 | Codex 현재 경로 | Gemini 현재 경로 |
|---|---|---|
| FS 범위 | read-only/workspace-write/full-access를 sandbox로 대응 | 명시적 정책을 거절 |
| Network | workspace-write의 network 설정을 대응. 다른 조합은 거절 | 명시적 정책을 거절 |
| Caller 승인 | handler 중재 | 명시적 요구 거절 |
| 지시·모델 | developerInstructions/model | agent instructions/model |
| 작업 위치·skills | cwd, skill 입력과 activation envelope | cwd, skillDir와 activation |

개정에서는 문맥 설정·작업 요구·승인 중재·실행 환경 집행을 분리한다. 지원하지 않는 요구를 생략해 기본값으로 실행하지 않는다. 지시를 user prompt 앞에 붙이는 것과 지속 지시를 전달하는 것은 같은 보장이 아니다. skill 자료 제공과 실행별 활성화도 구별한다.

## 상태·결과 매핑 원칙

규범은 [종결 확인의 근거](lifecycle-and-concurrency.md#종결-확인의-근거)다. 다음 표는 기존 조사·실험이 제공한 신호와 새 계약으로의 검증 과제를 구별한다. 아직 세 adapter의 새 판정 규칙을 실행 검증한 표가 아니다.

| 대상 | 기존 경로의 근거 후보 | 충분한 근거가 되기 위한 조건 | 남은 검증 |
|---|---|---|---|
| Codex | turn 종결 보고와 status, interrupt 요청 응답 | 종결 보고가 해당 Task의 위임 범위를 끝낸다는 대응과 사유를 확인. interrupt 요청 수락만으로 Cancelled를 만들지 않음 | turn/Task 범위, 완료·취소 경쟁, 하위 실행의 귀속과 보고 유실을 fixture·실연동으로 확인 |
| Gemini CLI | sendStream 관찰 종료 후 host가 합성한 completed/failed/cancelled 알림 | 관찰 loop 종료가 귀속된 실제 작업의 종결을 뜻하는지 SDK 계약·실행으로 입증. 합성 이벤트의 이름만으로 판정하지 않음 | stream 종료 후 계속되는 작업·도구, abort 뒤 실제 종료, 보고 유실·비협조적 작업을 구별 |
| Koog | graph 실행 coroutine과 등록 tool의 종료. 비협조적 tool 반환 전에는 기존 실험에서 실행이 남음 | 해당 coroutine의 종료가 귀속된 graph/tool 실행의 종결을 포괄하고 종료 사유를 확인할 수 있음 | 실험 밖으로 분리된 작업·외부 효과의 범위, production 수명 관리, 새 네 outcome 판정 검사 |

Adapter가 같은 범위의 충분한 근거를 확보했다면 그 결과를 전달해야 한다. 근거가 부족한 bounded 정리는 Unresolved다. 어떤 adapter에서도 stream·process 종료 자체를 항상 성공 또는 항상 미확정으로 고정하지 않는다. 구현 전환 시 표에 SDK/runtime revision, 보장 범위와 실제 통과 시나리오를 추가한다.

현재 host death → TRANSPORT 실패, close 유예 → CANCELLED 경로는 개정 대상이다. 먼저 확인한 사실을 판정한다.

- 작업 실패를 provider가 확인했다면 Failed로 분류한다.
- 실제 취소 종료를 확인했다면 Cancelled로 분류한다.
- 관찰·제어만 상실했다면 Unresolved를 전달한다.
- 정상 실행 종료라도 목표 달성·schema 검증을 자동 인정하지 않는다.

기존 인증·정책·예산·문맥·일시적 오류 매핑의 유용한 구분은 보존한다. provider의 unknown 오류는 공통 설명과 알려진 사실로 전달하고 raw 이벤트 해석을 소비자의 필수 책임으로 두지 않는다.

분류는 [공통 실패 분류](protocol-reference.md#상태와-종결-판정)를 따른다. 시작 응답이나 interaction 응답 확인을 잃은 경우에는 미수행으로 단정하지 않고 수락 미확정을 보존한다.

Codex의 session 누적 usage에서 baseline을 빼는 방식과 Gemini의 필드별 누적은 현재 구현 수단이다. reset·누락·scope를 검증해 Task/Session 측정을 섞지 않는다. 음수나 unknown을 임의의 0으로 보정해 정확한 측정처럼 보고하지 않는다.

공통 UsageChanged는 두 경로 모두 Task 누적 snapshot으로 제공한다. consumer가 provider에 따라 더하거나 교체하도록 분기하게 만들지 않는다. 반복 누적값을 두 번 더하지 않으며 실제 제공 가능한 Session 누적은 별도로 유지한다.

Tool/effect의 이름·인자 key 매핑은 버전별 fixture로 검증한다. identity fallback 충돌을 막고 효과 근거가 없으면 도구 관찰만 전달한다. 실제 변경 경로를 아는 경우에만 경로 정보를 제공한다.

## 검증 상태

[Koog 결과](koog-abstraction-validation-results.md)의 18개 실험은 실제 Koog runtime과 통제된 모델 경계를 사용했다. 기존 회귀 71개는 Codex/Gemini mapper·bridge fixture 등을 검사했다. 세 구현의 동일 업무·동일 새 계약·실모델 검증은 아직 완료되지 않았다.

Gemini SDK build entrypoint와 지시 옵션의 실제 요구 조건, Codex 승인 실제 payload, Koog production 저장·정리 및 선택 계약은 후속 연동에서 확인한다. [Testing](testing.md)의 공통 행동과 구현별 검사를 구분하여 결과를 갱신한다.
