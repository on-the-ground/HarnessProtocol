# 공통 시나리오 통합과 남은 검증

현재 요구 사례 연결은 [단계 2 G01 기록](requirement-admission-validation.md)을 따른다. C20/C21은 독립 요구 사례 판정으로 대체했고, 남은 미연결 정의는 46개다. 아래 단계 1 실행 수는 당시의 검증 기록이다.

2026-09-04. 단계 1은 검사 위치·목적·실행 증거를 통합하는 작업이다. 공개 Port의 의미를 변경하지 않는다. [기계 판독 목록](../harness-conformance/scenario-catalog.json)에 기존 48개 정의, SDK 공통/전용 20개, native 8개를 모두 대응시켰다. 76개 항목은 고유 업무 목적의 수나 통과 수가 아니다.

## 코드 배치

- HarnessLifecycleConformanceTest: 기존 testkit에서 순수 lifecycle 9개를 이동했다. Codex/Gemini의 실제 adapter에 RecordingBridge로 SDK 사실을 전달해 각각 실행한다.
- HarnessRuntimeConformanceTest: 필수 runtime 5개. 실제 입력·지시·문맥, 중첩/독립 waiter, 정리, 확인된 취소를 검사한다.
- HarnessRuntimeProfileConformanceTest: 현재 선택한 세 구성의 진단 지원·구조화 요구 거절 2개. 모든 adapter에 이 지원 조합을 강제하는 기본 계약이 아니다.
- HarnessRuntimePersistenceConformanceTest: Codex/Gemini에 연결된 실제 영속 문맥 재개 1개.
- HarnessRequirementsConformanceTest: 독립 profile별 지원·preflight·직접 호출을 실제 세 adapter에서 검사하는 동적 factory. 5개 profile·43개 요구 사례의 실행 기록은 G01 문서를 따른다.
- HarnessConformanceCoreTest/CleanupTest: 기존 29+19개 중 C20/C21을 요구 사례 판정으로 대체했다. 남은 27+19개는 아직 전체 HarnessFixture에 연결되지 않은 정의다. 아래 가정을 고치고 실제 경계에 연결해야 한다.
- SdkAdapterContractTest: 요청 전송·설정 투영·mailbox 해제·EOF·ID 정규화 등 SDK 경계 검사 11개와 lifecycle binding만 남겼다. 기존 AgentHarnessContractTest는 제거했다.

공통 판정은 conformance의 testFixtures에 있고 provider 설정·SDK JSON·모델 서버는 외부 binding에 있다. TaskLifecycleControl은 기존 TaskControl의 lifecycle 부분을 공유한다. 어떤 binding도 AgentTask state/outcome을 직접 설정하지 않는다.

목적이 겹쳐도 실제 runtime과 SDK 변환 경계의 증거를 합산해 인증하지 않는다. 미연결 C/K 정의는 대응 목적의 실행본과 다음 단계에서 병합하거나 부족한 조건을 보강한다. 기존 실행 검사를 삭제하고 미실행 정의로 대체하지 않았다.

## 연결 전에 바로잡을 가정

1. baseline/approval/questions라는 profile 이름과 cases.first()를 고정하고, persistence는 항상 거절·network ALLOWED는 항상 미확인이라고 가정한다. 독립 RequirementCase의 요구·검증·수락 기대값으로 선택해야 한다.
2. 늦은 구독의 첫 이벤트를 terminal로 고정한다. 계약은 terminal 보존과 유한 종료를 요구한다.
3. close/release 호출 뒤에만 timeout을 적용하거나 200ms 유예를 가정한다. 정리 진입을 동기화하고 실제 호출 시간·다중 자원의 total budget을 측정해야 한다.
4. runtime 소유 여부만으로 Failed를 확정하거나 관찰 상실 직후 RUNNING만 허용한다. 실제 종료 근거와 즉시 Unresolved의 적법성을 구별해야 한다.
5. 비협조적 작업의 gate를 열기만 하고 실제 후속 효과를 검사하지 않는다. effect count와 outcome 불변을 함께 확인하고 실패 시에도 남은 작업을 해제해야 한다.
6. Conditional persistence를 건너뛰거나 필요한 interface가 없으면 조용히 return한다. 지원한 구체적 사례에서 미이행은 실패여야 한다.
7. 승인 응답 전에 효과를 즉시 단정하거나 예상 답변을 주입해 문맥을 검증한다. 실제 수락·효과와 후속 모델 입력을 관찰해야 한다.
8. 임의 사용량 값을 모든 native runtime에 주입할 수 있다고 가정한다. 공통 회계 의미와 SDK 변환 검사의 증거를 구별해야 한다.

코드 목록의 beforeBinding에 해당 ID별 조건을 기록했다. 단계 1에서는 미연결 정의의 기대값을 고쳐 통과로 만들지 않았다. 이후 C20/C21은 특정 기능의 고정 상태 가정을 제거하고 독립 요구 사례 판정으로 대체했다.

## 다음 실행 순서

G01의 독립 profile/case 선택과 실제 수락 검사를 연결했다. 미확인 요구의 실제 사례와 나머지 기존 본문의 profile 전환은 남아 있다. 다음은 수락/응답 확인 유실(G02), interaction 경쟁(G03), 문맥 차단(G04), 부분 산출물·사용량(G05), 정리·후속 효과(G06)를 실제 세 adapter에서 검증한다. 이후 진단·권한·자원·구조화·usage·영속 조건(G07–G12)을 지원 시 이행/미지원 시 사전 거절로 검증한다.

통과한 기본 목적을 반복 구현하지 않고 아래 related ID의 실행본을 재사용한다. 지원하지 않는 선택 기능의 성공 시나리오를 강제로 만들지 않으며, 필수 동작이나 지원한다고 선언한 기능을 skip하여 완료하지 않는다. 실행마다 provider × scenario × profile × 증거 경계 × 결과를 기록한다.

## 전체 대응표

S는 이전 AgentHarnessContractTest의 원래 순번, R은 이전 NativeHarnessTest의 순번이다. C/K는 기존 Core/Cleanup 순번이다. related는 목적 중첩을 뜻하며 해당 C/K의 모든 조건을 이미 통과했다는 표시가 아니다. C20/C21은 요구 사례 factory로 대체했으며 나머지 C/K의 전체 fixture 연결 상태는 미연결이다. S는 Codex/Gemini SDK 경계에 연결돼 있고, R01–R07은 세 runtime, R08은 Codex/Gemini에 연결돼 있다.

| ID | 검사 | 현재 경계 | 관련 목적 |
|---|---|---|---|
| C01 | startTask accepts and returns a handle spanning several internal calls | 미연결 정의 | — |
| C02 | completed outcome matches terminal state and settles exactly once | 미연결 정의 | — |
| C03 | failed outcome matches terminal state and carries its kind | 미연결 정의 | — |
| C04 | cancelled outcome matches terminal state | 미연결 정의 | — |
| C05 | natural language failure without a classified kind reports UNKNOWN, not a fabricated kind | 미연결 정의 | — |
| C06 | pending clears and terminal state is confirmed before awaitOutcome returns, on completion | 미연결 정의 | — |
| C07 | pending clears and terminal state is confirmed before awaitOutcome returns, on failure | 미연결 정의 | — |
| C08 | completes without any event collector | 미연결 정의 | — |
| C09 | a slow collector observes a gap but still sees the terminal event last | 미연결 정의 | — |
| C10 | a late subscriber still receives the terminal event | 미연결 정의 | — |
| C11 | overlapping start on one session is rejected before it reaches the native boundary | 미연결 정의 | — |
| C12 | a session accepts its next task only once the previous one is terminal | 미연결 정의 | — |
| C13 | different sessions on one harness make progress concurrently without mixing context | 미연결 정의 | — |
| C14 | natural completion wins the race against a cancellation request | 미연결 정의 | — |
| C15 | cancellation requested after terminal is a no-op | 미연결 정의 | — |
| C16 | a normal completion with no captured output is Completed with a null output | 미연결 정의 | — |
| C17 | an actual empty string output is distinct from no output | 미연결 정의 | — |
| C18 | completion's null output does not erase output already captured earlier | 미연결 정의 | — |
| C19 | completion does not imply schema-valid structured output | 미연결 정의 | — |
| C20 | an incompatible session requirement is rejected before any handle is issued | 실제 요구 사례로 대체 | G01 |
| C21 | an unconfirmed session requirement is distinguished from a confirmed rejection | 공통 분기 존재, 실제 사례 미확보 | G01 |
| C22 | a request-level rejection is distinguished from a post-handle failure | 미연결 정의 | — |
| C23 | the same work key in two different tasks does not cross-contaminate effect counts | 미연결 정의 | — |
| C24 | whitespace-only input text is preserved verbatim, not trimmed | 미연결 정의 | — |
| C25 | null instructions and explicit empty instructions are distinguished | 미연결 정의 | — |
| C26 | only activated skills are observed, even when more are provided | 미연결 정의 | — |
| C27 | output captured before a failure is still recovered in the Failed outcome | 미연결 정의 | — |
| C28 | output captured before a cancellation is still recovered in the Cancelled outcome | 미연결 정의 | — |
| C29 | output captured before an Unresolved settlement is still recovered, not reported as final | 미연결 정의 | — |
| K01 | release settles an active task as cancelled when confirmation arrives within budget | 미연결 정의 | — |
| K02 | release settles an unconfirmed task as Unresolved once the cleanup budget elapses | 미연결 정의 | — |
| K03 | a completion that arrives right after cleanup starts is still recovered within budget | 미연결 정의 | — |
| K04 | harness close settles active tasks across sessions within the total cleanup budget | 미연결 정의 | — |
| K05 | uncooperative work yields Unresolved with a cancellation-unconfirmed reason, not a fabricated Cancelled | 미연결 정의 | — |
| K06 | false-cancel detection - work observed after Unresolved does not retroactively change the settled outcome | 미연결 정의 | — |
| K07 | a session stays blocked for new tasks after its last task was Unresolved, even after release | 미연결 정의 | — |
| K08 | a brand-new session on the same harness is unaffected by another session's block | 미연결 정의 | — |
| K09 | ending only the inner turn does not terminate the task | 미연결 정의 | — |
| K10 | pure observation loss without a terminal report settles Unresolved with an observation-lost reason | 미연결 정의 | — |
| K11 | a runtime that owned the work reports a confirmed transport failure, not Unresolved | 미연결 정의 | — |
| K12 | a runtime that did not own the work leaves it outstanding rather than fabricating a result | 미연결 정의 | — |
| K13 | a start rejected before delivery does not block the session and can be retried | 미연결 정의 | — |
| K14 | losing the start acceptance acknowledgement blocks the session, distinct from a clean rejection | 미연결 정의 | — |
| K15 | usage deltas accumulate and unknown fields stay unknown rather than becoming zero | 미연결 정의 | — |
| K16 | a fresh usage snapshot resets the baseline instead of double-counting prior deltas | 미연결 정의 | — |
| K17 | task and session usage are reported and preserved separately | 미연결 정의 | — |
| K18 | a late notification after the outcome is confirmed does not overwrite it | 미연결 정의 | — |
| K19 | reopening a persistent session surfaces the storage's canonical id, not the caller's raw string | 미연결 정의 | — |
| S01 | compatible specs reach the bridge with their intent intact | SDK 전용 회귀 | C20, C21, C25, C26 |
| S02 | creates a session and completes an execution | SDK 전용 회귀 | C01, C02 |
| S03 | state is terminal before awaitOutcome returns | SDK 경계의 공통 판정 | C02, C06 |
| S04 | completes without an event collector | SDK 경계의 공통 판정 | C08 |
| S05 | slow collector does not block lifecycle | SDK 경계의 공통 판정 | C09 |
| S06 | failure is reported through state and awaitOutcome | SDK 경계의 공통 판정 | C03 |
| S07 | cancellation is reported through state and awaitOutcome | SDK 전용 회귀 | C04 |
| S08 | cancel after terminal is a no-op | SDK 전용 회귀 | C15 |
| S09 | completion wins the race against cancel | SDK 경계의 공통 판정 | C14 |
| S10 | terminal is exactly once and last | SDK 경계의 공통 판정 | C02, K18 |
| S11 | stream ending without a terminal leaves outcome unresolved | SDK 전용 회귀 | K10, K07 |
| S12 | stream failure leaves outcome unresolved | SDK 전용 회귀 | K10, K07 |
| S13 | release is called after terminal | SDK 전용 회귀 | — |
| S14 | rejects overlapping tasks on one session | SDK 전용 회귀 | C11, C12 |
| S15 | different sessions execute concurrently | SDK 경계의 공통 판정 | C13 |
| S16 | harness close without native termination evidence settles unresolved | SDK 경계의 공통 판정 | K04 |
| S17 | overflow is explicit and terminal survives | SDK 경계의 공통 판정 | C09 |
| S18 | session release is idempotent and rejects further tasks | SDK 전용 회귀 | K07 |
| S19 | session release settles an active execution | SDK 전용 회귀 | K02 |
| S20 | reopen uses the session id returned by the host | SDK 전용 회귀 | K19 |
| R01 | native task completes without an observer and preserves state and output | 실제 runtime | C01, C02, C06, C08 |
| R02 | same session carries prior native context while a new session stays isolated | 실제 runtime | C13 |
| R03 | unsupported task requirements are rejected before a native model call | 실제 runtime | C22 |
| R04 | overlap is rejected and cancelling one waiter does not cancel native work | 실제 runtime | C11, C12 |
| R05 | close bounds active native work and settles the waiter | 실제 runtime | K04 |
| R06 | independent semantic and diagnostic observers finish and late subscription retains terminal | 실제 runtime | C02, C10, C15 |
| R07 | explicit cancellation waits for native termination and leaves the session reusable | 실제 runtime | C04, C12 |
| R08 | persistent reopen preserves actual context and desired instructions across harness recreation | 실제 runtime | K19 |

## 실행 중 발견한 정리 문제

전체 실행에서 Codex의 구조화 요구 사전 거절 assertion은 통과했지만, 종료 후 JUnit이 작업 폴더를 삭제하지 못했다. 부모 host의 강제 종료를 기다리는 보완만으로는 같은 실패가 남았다. 프로세스 목록을 관찰하니 종료 유예 중 생긴 git 하위 프로세스가 최초 snapshot에 없었다. 알려진 각 프로세스에서 자식을 종료 단계마다 다시 수집하고, 부모도 기존 마지막 대기 시간 안에서 함께 기다리도록 process bridge를 보완했다. 대기 시간 상수와 공개 계약은 유지했다.

[첫 전체 실행](../harness-native-integration/evidence/scenario-consolidation-first-run.json), [부모 대기 보완 후 실행](../harness-native-integration/evidence/scenario-consolidation-parent-wait-run.json), [프로세스 관찰 기록](../harness-native-integration/evidence/scenario-consolidation-cleanup-diagnosis.json)을 보존했다. 이 수정은 관찰된 프로세스 정리 누락을 다루며, 미연결 cleanup 시나리오 전체의 통과를 뜻하지 않는다.

## 단계 1 검증 결과

`./gradlew.bat --offline test :harness-conformance:testFixturesClasses -PnativeHarnessTests --console=plain`은 통과했다. 변경된 consumer suite를 실행했고, 변경 없는 검사 일부는 Gradle up-to-date 결과를 재사용했다. 루트 JVM 결과는 105개이며 실패·오류·건너뜀은 0개다. 실제 runtime 25개가 이 집계에 포함된다.

- 공통 정의 17개: SDK 경계 lifecycle 9개 × Codex/Gemini = 18회, 실제 runtime 7개 × 세 구현체 + 영속 재개 1개 × Codex/Gemini = 23회. 합계 41회다.
- SDK 전용 정의 11개 × Codex/Gemini = 22회다. 기존 SDK 20개와 native 8개 정의의 검사 이름 및 실행 대응을 보존했다.
- 미연결 Core/Cleanup 48개는 컴파일만 확인했다. 위 41회와 합쳐 새로 48개를 통과했다고 보고하지 않는다.

[provider별 실행 대응과 검증 기록](../harness-native-integration/evidence/scenario-consolidation.json)은 63개 실행을 JUnit 검사 이름·suite·시각에 대응시킨다. Host·독립 Koog 실험·sample은 이번 단계에서 재실행하지 않았으며 [직전 검증](../harness-native-integration/evidence/before-scenario-consolidation.json)에 남겼다. README/docs 상대 파일 링크 누락은 0개이고, 현재 Kotlin 소스에는 legacy Port 및 삭제한 AgentHarnessContractTest 참조가 없다.
