# AHP 추상 개정과 용어

작성일: 2026-09-04  
상태: README와 docs가 따르는 개정 설계 기준과 목표 용어. 코드·KDoc·adapter 전환은 후속 통합 단계에서 수행한다.  
최상위 기준: [AHP 설계 선언](../AHP_CHARTER.md)  
실험 근거: [Koog 검증 결과](koog-abstraction-validation-results.md)

## 1. 설계 기준

AHP는 비즈니스 애플리케이션이 에이전트 작업을 위임하고, 필요한 조건을 요구하고, 진행 중 판단에 참여하며, 결과와 종료 상태를 받아 후속 행동을 결정하는 계약이다.

0.1.0의 타입·시그니처·구현은 개정의 제약 조건이 아니다. 목적에 맞지 않는 추상은 수정·분리·제거하고 기존 adapter를 새 계약에 맞춘다. 목적과 보장이 같은 경우에는 익숙한 이름을 유지한다. 이름 변경만으로 책임 분리를 대신하지 않는다.

Koog 실험은 현재 계약을 구현할 수 있는지를 검증했다. 구현할 수 있다는 사실만으로 모든 하네스에 그 계약을 필수로 요구해야 한다고 결론 내리지 않는다. 특히 adapter에 파일 저장소를 추가할 수 있다는 사실은 영속성을 기본 Session에 포함해야 할 근거가 아니다.

이 문서는 실험 이후 합의한 개정 방향이다. README와 docs의 계약 설명은 이 방향으로 통일하며, 과거의 유지 권고를 현재 규범으로 병기하지 않는다. 실험 관찰과 현재 구현의 wire·artifact 정보는 증거·구현 상태로 구별한다. [Semantic contract](semantic-contract.md)가 판단 기준을, [Protocol reference](protocol-reference.md), [Lifecycle](lifecycle-and-concurrency.md), [Event contract](event-contract.md)가 동작 의미를 구체화한다.

## 2. 중심 개념

| 용어 | 의미 | 포함하지 않는 보장 |
|---|---|---|
| `AgentHarness` | 애플리케이션이 에이전트 작업을 위임하는 Port | SDK, process, graph 또는 원격 서버라는 제공 방식 |
| `AgentTask` | 한 번 위임한 작업을 식별하고 관찰·개입·종결 판정을 받는 handle | 모델 호출 하나, provider turn 하나, graph node 하나와의 일대일 대응 |
| `AgentSession` | 여러 작업이 문맥을 공유하는 논리적 범위 | 자동적인 영속 보관, checkpoint 복구, 실행 중 작업의 재접속 |
| `InteractionRequest` / `InteractionResponse` | 작업을 진행하기 위해 필요한 외부 판단·정보의 요청과 응답 | 모든 응답을 승인 enum이나 임의 JSON으로 취급하는 모델 |
| `TaskOutcome` | 호출자에게 확정해 전달하는 작업의 종결 판정 | 업무 목표의 달성이나 실제 작업 종료가 언제나 확인됐다는 가정 |
| `TaskOutput` | 작업이 호출자에게 전달한 산출물 | 산출물이 존재한다는 이유만으로 목표 달성·schema 유효성을 인정하는 것 |

Task는 하나의 위임 단위다. 그 안에서 수행하는 도구 호출과 효과는 하위 작업으로 관찰한다. 여러 Task를 조합하는 장기 업무 workflow나 목표 관리 기능을 이 이름만으로 도입하지 않는다.

Session은 업무 문맥 공유를 표현한다. LLM prompt의 토큰·message 배열·내부 저장소를 public 객체로 노출하는 `Context`와 혼동하지 않도록 `AgentSession`이라는 이름을 유지한다. 한 작업만을 위해 session을 열더라도 영속 저장소를 갖추도록 강제하지 않는다.

## 3. 이름 전환표

다음은 후속 공개 API의 목표 이름이다. 동작이 달라지는 행은 호환 alias나 기계적인 치환으로 구현하지 않는다.

| 0.1.0 | 새 이름 / 배치 | 변경 이유 |
|---|---|---|
| `AgentExecution` | `AgentTask` | 관점의 중심을 내부 실행 루프에서 위임받은 작업으로 옮긴다. |
| `ExecutionId` / `executionId` | `TaskId` / `taskId` | 식별 대상은 한 번의 업무 위임이다. |
| `AgentSession.execute(input)` | `AgentSession.startTask(input)` | 완료까지 수행하는 함수와 구별하여 작업 시작 및 handle 반환을 드러낸다. |
| `AgentInput` | `TaskInput` | 작업에 전달하는 입력임을 명확히 한다. 작업 중 질문에 대한 답변과도 구별한다. |
| `ExecutionState` | `TaskState` | Task handle에서 관찰하는 상태다. 아래 종결 의미도 함께 개정한다. |
| `WAITING` | `AWAITING_RESPONSE` | 승인·질문 등에 대한 외부 응답 대기를 나타낸다. 일반 지연이나 rate limit 대기와 구별한다. |
| `AgentExecution.cancel()` | `AgentTask.requestCancellation()` | 요청 전달이 실제 취소 완료를 뜻하지 않음을 호출 지점에서 드러낸다. |
| `awaitResult()` | `awaitOutcome()` | 성공뿐 아니라 실패·취소·종료 미확정의 종결 판정을 회수한다. 반환·예외 계약도 함께 개정한다. |
| `AgentResult` | `TaskOutcome`과 `TaskOutput`으로 책임 분리 | 종료 사유·실패와 사용자에게 전달한 산출물을 구별한다. 단순 타입 이름 변경이 아니다. |
| `finalMessage` | 텍스트 산출물 `TaskOutput.Text`의 내용으로 이동 | 텍스트를 모든 업무 결과의 유일한 형태로 고정하지 않는다. 최종 field 배치는 결과 모델 설계에서 정한다. |
| `AgentEvent` | `TaskEvent` | 작업 범위의 의미 이벤트라는 점을 명확히 한다. |
| `ExecutionStarted/Completed/Failed/Cancelled` | `TaskStarted/Completed/Failed/Cancelled`, 종료 미확정은 `TaskUnresolved` | 이벤트·식별자·상태의 주어를 일치시키고 종료 미확정을 구별한다. |
| 기본 Port의 `resumeSession` | 영속성 선택 계약의 `reopenSession` | 보관된 업무 문맥에 대한 새 handle 획득임을 나타낸다. 중단된 작업의 재실행·복구와 구별한다. |
| `ProviderEventObserved` | 별도 진단 계약의 `ProviderDiagnostic` | 원본 보존을 모든 Task의 필수 의미 이벤트에서 분리한다. 공급자 payload는 진단 정보임을 이름에 남긴다. |

`TaskOutcome`으로 결과를 통합할 때 현재 실행 실패·취소 예외를 이름만 바꾸어 중복 계약으로 남기지 않는다. handle 생성 전 호출 실패와 handle을 받은 작업의 outcome을 구분한다. 코루틴 waiter 자체의 취소는 여전히 작업 취소와 별개다.

`TURN_COMPLETED`, `TURN_INTERRUPTED` 같은 public 세부 용어도 provider turn을 전제하지 않도록 전환한다. 특히 취소 요청, 작업 종결, handle 해제는 서로 다른 원인이므로 모두 `TASK_CANCELLED`로 치환하지 않는다. 예를 들어 요청에 의한 interaction 정리는 `CANCELLATION_REQUESTED`, 종결로 인한 정리는 `TASK_ENDED`처럼 관찰한 원인을 사용한다. 최종 enum 목록은 상태·interaction 계약과 함께 확정한다.

## 4. 이름을 유지할 것과 개념을 분리할 것

| 대상 | 처리 |
|---|---|
| `AgentHarness` | 유지. Port의 역할로 정의하며 SDK client나 OS 자원 소유자라는 전제를 제거한다. |
| `AgentSession`, `SessionId` | 유지. 기본 ID에 영속 재개 가능성을 암묵적으로 부여하지 않는다. 영속 보관을 요구한 session의 식별·재개 보장은 별도로 명시한다. |
| `InteractionRequest`, `InteractionResponse`, `InteractionId`, `respond` | 유지. Approval과 Question/Answer가 공유하는 전달·대기·일회 응답 계약으로 확장한다. |
| `ApprovalDecision` | 유지. 승인에만 사용하는 구체적 결정이다. 질문 답변을 여기에 추가하지 않는다. |
| `ToolCallChanged`, `EffectChanged` | 유지. 도구 수행과 외부 효과의 차이는 업무 관찰에 의미가 있다. graph node와 tool을 동일시하지 않는다. |
| `validate`, `CompatibilityReport` | 유지. 요구를 지킬 수 있는지 판단하고, 불가능한 요구를 실제 호출 경계에서도 거절한다. |
| `AgentSpec`, `ExecutionPolicy` | 필드의 책임을 먼저 분리한다. session 설정, 작업 요구, 승인 중재, 실행 환경 제약을 그대로 둔 채 `TaskSpec`/`TaskPolicy`로 치환하지 않는다. |
| `workingDirectory`, 로컬 skill 경로, FS/network 집행 | 명시적인 실행 환경·자원 관련 선택 계약으로 옮긴다. 실제 계약 없이 범용 `Resource`나 임의 options map으로 바꾸지 않는다. |
| `SdkBridge`, `RecordingBridge` | process adapter 구현·테스트에서 유지. 공통 Port와 공통 적합성 검사의 필수 전제에서 제거한다. |

선택 계약은 보장을 약하게 만드는 장치가 아니다. 요구한 영속성, 권한 제한, 질문 대응, 출력 형태를 지원한다고 선언했다면 정확히 이행해야 한다. 필수 요구를 조용히 무시하거나 기본값으로 대체하지 않는다. 지원 탐색과 요구 전달의 구체적 API는 계약 확정 단계에서 설계한다.

## 5. 결과와 종료 용어의 의미

`TaskState`는 Task handle이 관찰·확정한 상태다. `TaskOutcome`은 호출자에게 전달할 종결 판정이며 `TaskOutput`은 그 과정에서 얻은 산출물이다. 이 세 이름으로 같은 사실을 중복 표현하지 않도록 state와 outcome의 대응을 정의해야 한다.

- `Completed`: 실행 종료와 그 결과를 확인했다. 요청한 업무 목표 달성은 별도 판단이며 반복 한도 등 종료 사유를 보존한다.
- `Failed`: 작업 실패가 확인됐다. 통신이 끊겼다는 사실만으로 외부 작업의 실패를 확정하지 않는다.
- `Cancelled`: 취소에 따른 실행 종료가 확인됐다. 이미 발생한 효과의 rollback을 뜻하지 않는다.
- `Unresolved`: 관찰·제어를 끝내는 시점에 실제 종료 결과를 확인하지 못했다. 이후에도 작업이나 효과가 발생할 수 있다.

이 네 가지는 결과 의미의 분류다. 대응 상태와 전이·정리 규칙은 [Lifecycle](lifecycle-and-concurrency.md)을 따른다. 공개 sealed 타입의 최종 필드와 세부 오류 분류는 [전환 계획](port-revision-plan.md)에서 확정한다. `Unresolved`도 호출자가 유한하게 회수할 수 있는 outcome이어야 하며 무한 대기로 대신하지 않는다. 한 handle의 종결 판정 이후 추가 확인을 어떻게 제공할지는 별도 검토하고, 새 enum 하나로 재접속 기능까지 지원한다고 간주하지 않는다.

`close`와 `release`는 자원·handle 정리라는 익숙한 이름을 유지한다. 명시적 취소 요청과 bounded cleanup을 수행하더라도, 유예 시간 경과만으로 `Cancelled`를 합성하지 않는다. 작업 종료가 미확정이면 실제 종료와 문맥 일관성을 확인하기 전 같은 session의 새 작업을 거절한다. 구체적인 확인·복구 수단은 후속 구현에서 검증한다.

구조화 산출물은 요청한 schema와 검증 책임을 명시하는 선택 계약으로 다룬다. 텍스트 전달은 계속 지원하고, 진단 payload나 tool의 vendor별 result에서 업무 결과를 추출하도록 소비자에게 요구하지 않는다. 완료·부분 산출물·검증 실패를 어떻게 연결할지는 결과 모델 설계에 포함한다.

## 6. 전환 범위

후속 통합에서는 위 책임 분리와 상태·결과 의미에 따라 미확정 공개 필드·선택 계약 API를 구체화하고 이름을 코드에 적용한다. 공개 interface뿐 아니라 식별자, 이벤트, 실패 모델, KDoc, 컴파일 가능한 예제와 공통 테스트의 용어를 일치시킨다. README와 reference 문서는 개정된 계약 기준이며 구현이 이를 따라간다. 내부 SDK의 thread/turn, Koog graph 등의 이름은 각 adapter에서 필요에 따라 유지한다.

0.1.0의 강한 보장과 새 계약이 다를 때 typealias로 호환되는 척하지 않는다. 특히 영속성, `awaitOutcome`의 결과 전달, 종료 미확정 처리는 소비자 migration이 필요한 의미 변경이다. 이전 사용 사례가 요구한 보장을 새 계약에서 어떻게 명시할지 함께 제공한다.

실험 코드·실행 기록은 검증 당시 0.1.0의 재현 자료이므로 소급 rename하지 않는다. 새 계약에 맞춘 Koog 구현과 검증은 후속 통합의 산출물로 관리한다. 세 adapter의 공통 행동 검사는 `AgentHarness`/`AgentTask` 등 공개 계약을 기준으로 하고 provider별 준비 방식은 fixture로 분리한다.

본 문서는 현재 공개 API 사용법이 아니다. 기존 구현을 바꾸는 작업의 기준이며, 미확정 항목을 해결하기 전까지 세 구현의 통합 완료나 새 계약의 실행 검증을 주장하지 않는다.
