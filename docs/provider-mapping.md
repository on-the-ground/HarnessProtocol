# Provider mapping

이 문서는 [개정 계약](protocol-reference.md)의 목적을 Codex·Gemini CLI·Koog의 서로 다른 수단에 대응시키고 구현 전환 상태를 기록한다. SDK 기능이 공통 목적을 결정하지 않는다.

현재 세 adapter는 새 공개 Port에 연결돼 있다. 실제 runtime과 통제된 모델 응답의 검증 범위·revision·남은 gate는 [실제 adapter 검증](native-port-validation.md)을 따른다. 이 연결과 일부 시나리오 통과를 새 계약 전체의 적합성 인증으로 취급하지 않는다. Koog 격리 실험은 별도의 이전 증거다.

## 목적별 대응과 전환

| 공통 목적 | Codex의 현재 수단 | Gemini CLI의 현재 수단 | Koog의 현재 수단 / 남은 전환 |
|---|---|---|---|
| AgentHarness | Python CodexClient와 App Server 연결 | Node SDK host | Kotlin graph runtime 직접 연결. bridge 공통화 불필요 |
| Session 문맥 | 실제 thread의 후속 모델 입력 | SDK session의 실제 이력 | 종료 전 확보한 native graph prompt history를 다음 작업에 연결 |
| 영속 보관·reopen | namespace 구성 시 thread/resume, 설정 변경 거절 | namespace 구성 시 resumeSession + initialize, desired 지시 반영 | production 구성은 미지원. 실험 파일 저장소의 이관은 후속 |
| Task 시작 | turn/start와 notification | sendStream | graph run. 여러 LLM/tool 호출을 하나의 업무 위임으로 묶음 |
| Caller 승인 | handler → pending decision → 응답 | CallerDecides 요구 거절 | bare ToolRegistry 구성은 거절. 실험의 승인 gate 이관은 후속 |
| 질문·답변 | 기존 공개 AHP 경로 없음 | 기존 공개 AHP 경로 없음 | native callback은 가능. 새 Question/Answer 전달 계약 필요 |
| 취소 요청 | pending handler 해제 후 turn/interrupt | AbortController.abort | coroutine 취소. 비협조적 도구의 실제 종료 여부는 별도 확인 |
| Outcome | 해당 turn의 종결 보고를 네 outcome으로 분류, 관찰 유실은 Unresolved | inner finished는 usage 경계, sendStream 전체 종료의 host 알림으로 종결 | 실제 graph/tool 정리 뒤 종결. 비협조적 작업의 유예 만료는 Unresolved |
| 산출물 | final-answer 메시지, 부족하면 관찰한 delta | 누적 content | String 결과. TaskOutput과 schema 보장 분리 필요 |
| Tool/effect | tool·command·file·search item lifecycle | tool request/response | 실제 registry 호출 hooks. 내부 node는 자동으로 tool이 되지 않음 |
| Usage/context | token usage, compaction 알림 | usageMetadata, chat-compressed | 메타데이터가 없으면 unknown. 전체 측정·compaction 미검증 |
| 진단 | 원본 method/payload | 원본 type/value | hooks/internal 객체. ProviderDiagnostic 선택 경로로 분리 |

같은 purpose를 서로 다른 수단으로 이행할 수 있다. provider thread를 그대로 노출하거나 Koog graph에 맞춰 Port를 바꾸지 않는다. TaskId·SessionId·WorkId의 외부 의미는 native ID 형식과 분리한다.

## 현재 Codex 경로의 구현 정보

Host는 고수준 Codex/AsyncCodex가 아닌 `openai_codex.client.CodexClient`를 사용하며 requirements는 0.147.0을 고정한다. 근거는 [client 조사](spikes/2026-09-03-codex-low-level-client.md)다.

- thread/start·resume의 developerInstructions, model, cwd와 정책 wire 필드를 구성한다.
- ephemeral retention 요구는 `thread/start.ephemeral=true`로 전달하고 반환된 `thread.ephemeral`을 `AgentSession.disposition.retention`으로 보고한다. false·누락을 요청값으로 덮어쓰지 않는다.
- pinned SDK의 ephemeral 설명은 in-memory/no disk materialization까지다. account-wide remote retention이나 Codex Desktop Recents 비노출의 독립 응답은 없으므로 user-history visibility는 UNKNOWN이며 Hidden 요구는 UNCONFIRMED로 사전 거절한다.
- PROVIDER_DEFAULT는 approval 필드를 생략한다. SDK convenience default로 의미를 바꾸지 않는다.
- CALLER_DECIDES는 caller 응답을 기다린다. reader thread를 막는 handler의 pending 대기를 먼저 풀고 interrupt/close한다.
- 현재 handler는 DENY_ALL에서 decline하며, PROVIDER_DEFAULT/AGENT_REVIEWED 경로에 예상 밖 요청이 오면 decline과 경고를 사용한다.
- APPROVE_FOR_SESSION 같은 선택의 지원 여부는 실제 요청이 제시한 결정과 scope로 검증한다. 일반적인 세션 권한 확대를 추론하지 않는다.

실제 App Server 승인 payload와 결정 문자열 fixture는 기존 검증에서 미확보였다. stub server 통과를 실제 provider 검증으로 바꾸어 서술하지 않는다. 제3자 Kotlin 기반 교체는 [별도 검토](codex-agent-adoption-review.md)의 대상이다.

## 현재 정책·자원 제약

아래는 구성된 process adapter의 지원 범위다. native 투영과 실제 집행의 검증 범위는 구별하며 새 core의 보편적 필드 목록이 아니다. Koog를 포함한 전체 구성표는 [실제 adapter 검증](native-port-validation.md#지원-범위와-남은-gate)을 따른다.

| 요구 | Codex 현재 경로 | Gemini 현재 경로 |
|---|---|---|
| FS 범위 | read-only/workspace-write/full-access를 sandbox로 대응 | 명시적 정책을 거절 |
| Network | workspace-write의 network 설정을 대응. 다른 조합은 거절 | 명시적 정책을 거절 |
| Caller 승인 | handler 중재 | 명시적 요구 거절 |
| 지시·모델 | developerInstructions/model | agent instructions/model |
| 작업 위치·skills | cwd, skill 입력과 activation envelope | cwd, skillDir와 activation |
| Context retention | ephemeral 요구를 thread/start에 전달하고 thread.ephemeral을 관측 | 명시적 요구를 거절 |
| User history visibility | 독립 관측이 없어 Hidden 요구는 UNCONFIRMED | 명시적 요구를 거절 |

개정에서는 문맥 설정·작업 요구·승인 중재·실행 환경 집행을 분리한다. 지원하지 않는 요구를 생략해 기본값으로 실행하지 않는다. 지시를 user prompt 앞에 붙이는 것과 지속 지시를 전달하는 것은 같은 보장이 아니다. skill 자료 제공과 실행별 활성화도 구별한다.

## 상태·결과 매핑 원칙

규범은 [종결 확인의 근거](lifecycle-and-concurrency.md#종결-확인의-근거)다. 다음 표는 현재 adapter의 근거와 실제 확인 범위를 구별한다. SDK/runtime revision과 상세 실행 목록은 [native 검증 기록](native-port-validation.md)을 따른다.

| 대상 | 현재 판정 근거 | 실제 확인한 범위 | 남은 검증 |
|---|---|---|---|
| Codex | 해당 turn의 종결 보고·status. interrupt 수락 자체는 취소 완료가 아님 | 실제 App Server 완료·명시 취소·정리·취소 후 문맥 재사용 | 하위 실행의 귀속, 완료·취소 경계 경쟁, 보고 유실 |
| Gemini CLI | 내부 finished와 구별한 sendStream 전체 종료 및 host 보고 | 실제 SDK 완료·명시 취소·정리·취소 후 문맥 재사용 | 비협조적·분리된 도구, 보고 유실, 종료 경계 경쟁 |
| Koog | 실제 graph/tool 및 소유 자원 정리 종료 | 완료·실패·취소, 유예 초과 Unresolved 이후 실제 파일 효과, 부분 산출물·문맥 보존 | graph 밖 분리 작업, 다중 자원·유예 경계 경쟁 |

Adapter가 같은 범위의 충분한 근거를 확보했다면 그 결과를 전달해야 한다. 근거가 부족한 bounded 정리는 Unresolved다. 어떤 adapter에서도 stream·process 종료 자체를 항상 성공 또는 항상 미확정으로 고정하지 않는다. 추가 시나리오 검증 결과는 이 표와 native 기록에 반영한다.

새 Port는 기존의 host death → TRANSPORT 실패, close 유예 → CANCELLED 규칙을 사용하지 않는다. 먼저 확인한 사실을 판정한다.

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

[Koog 결과](koog-abstraction-validation-results.md)의 18개는 이전 계약의 실험 기록이다. 현재는 세 adapter에 같은 기본 시나리오를 적용한 21개와 구현별 4개, 총 25개 native 검사가 통과했다. 현재 Port로 이전한 SDK·bridge·bundle 회귀 71개와 공개 값 타입 9개는 별도다. 모든 계약 시나리오의 적합성과 실모델 검증은 완료 범위가 아니다.

Gemini SDK build entrypoint·지시 전달 결함, Codex 재개 설정 변경의 제한, 세 runtime의 기본 동작과 Koog 비협조적 정리는 [실제 adapter 검증](native-port-validation.md)에 새 증거로 기록했다. Codex 실제 승인 payload·효과, Koog production 저장소 및 선택 계약 전체 검증은 남아 있다. 독립 Koog 실험의 승인·질문·보관 구성도 현재 Port로 이전했으며 production 기본 지원과 구별한다. [Testing](testing.md)의 공통 행동과 구현별 검사를 구분한다.
