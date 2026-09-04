# Protocol reference

이 문서는 **개정된 AHP의 의미와 목표 명칭**을 정의한다. 현재 Kotlin API와 KDoc은 전환 전이며 아래 예시는 새 계약을 설명하는 개념 예시다. 미확정 시그니처·필드는 [전환 계획](port-revision-plan.md)에서 관리한다. 최상위 기준은 [설계 선언](../AHP_CHARTER.md)과 [Semantic contract](semantic-contract.md)다.

## 기본 모델

| 개념 | 호출자가 얻는 의미 |
|---|---|
| `AgentHarness` | 실행 책임을 위임받는 Port와 요구 호환성 판단 |
| `AgentSession` | 여러 작업이 문맥을 공유하는 논리적 범위 |
| `AgentTask` / `TaskInput` | 한 번의 업무 위임에 대한 관찰·개입·종결 handle과 그 입력 |
| `TaskState` / `TaskOutcome` | handle에서 관찰한 상태와 확정하여 전달하는 종결 판정 |
| `TaskOutput` / `TaskEvent` | 업무 산출물과 작업 범위의 의미 이벤트 |

내부 모델 요청이나 실행 그래프는 이 객체들과 일대일 대응할 필요가 없다.

## AgentHarness와 요구 검증

`provider`는 제공 경계의 식별자다. adapter 선택과 지원 판단은 구성 경계에서 수행하고 portable 업무 판단은 vendor 클래스나 원본 payload를 해석하지 않는다.

`validate`는 요청한 의미를 보존할 수 있는지 진단한다. 검증 대상은 문맥 설정, 작업 요구, 선택 계약의 각 적용 범위에 맞춰 구성한다. 기존 `AgentSpec`을 그대로 받는 시그니처를 최종안으로 확정한 것은 아니다.

- 필수 요구를 보존하지 못하면 error로 거절한다. warning이나 provider default로 대체하지 않는다.
- 사전 검증을 생략하더라도 session 생성·작업 시작·선택 계약 호출의 실제 경계에서 다시 검증한다.
- 설정 호환성이 인증·네트워크·실행 성공까지 보장하지는 않는다.
- default, 값 생략, 명시적인 빈 값이 서로 다른 의미라면 그 차이를 보존한다.

`createSession`은 새 문맥 공유 범위의 handle을 만든다. 영속 conversation 생성이나 OS process 시작을 기본 의미에 포함하지 않는다. `close`는 소유한 handle과 자원을 정리한다. 실제 작업 종료 확인은 별도의 outcome 판정이다.

## 식별자와 입력의 기본 보장

식별자는 비어 있지 않은 opaque 값이다. consumer가 native ID의 구조를 해석하거나 임의로 합성하지 않는다. `TaskId`는 소유 harness 안에서 작업을 구별하고, `WorkId`와 `InteractionId`는 해당 Task 안에서 대상을 구별한다. 서로 다른 Task에서 같은 하위 ID가 나타나도 섞이지 않아야 한다. provider가 같은 작업의 tool/effect를 식별한 경우 같은 WorkId로 연결한다.

`TaskInput.Text`는 빈 문자열을 거절하되 공백만 있는 문자열을 임의로 trim하거나 다른 값으로 바꾸지 않는다. caller 입력과 지속 지시의 의미를 유지한다. 요청한 skill 활성화 등 provider별 envelope를 구성할 수 있지만 다른 지시나 업무 입력으로 조용히 대체하지 않는다.

## AgentSession과 영속성

`SessionId`는 제공 경계 안의 opaque 식별자다. 기본 ID만으로 재시작 이후의 보관·재개 가능성을 추론하지 않는다.

`startTask(input)`은 작업을 받아들이고 `AgentTask` handle을 반환한다. 작업 완료까지 기다리는 연산이 아니다. 같은 session의 문맥을 공유하는 작업은 순차적으로 시작하며 진행 중 작업이 있으면 새 요청을 provider에 보내기 전에 거절한다.

이전 작업의 outcome이 `Unresolved`이면 handle은 종결됐어도 실제 작업이 남아 있을 수 있다. 문맥의 안전성을 확인하기 전 같은 session에서 새 작업을 시작하지 않는다. 서로 다른 session은 논리적으로 격리되며 실제 병렬 처리량은 보장하지 않는다.

`release`는 해당 session handle을 정리한다. 작업에 취소를 요청하고 제한된 시간 동안 종료를 확인한다. 이후 handle은 사용할 수 없다. 영속 보관을 요청하지 않았다면 release 이후의 문맥 보존은 보장하지 않는다.

영속성은 [선택 계약](capability-candidates.md)이다. 보관 범위·수명·저장 성공·재개 조건을 명시해야 한다. 지원한 경우 release나 harness 재생성 이후에도 약속한 범위에서 문맥을 다시 열 수 있어야 한다.

`reopenSession`은 보관된 문맥의 새 handle을 얻는다. 모르는 ID나 다른 제공 경계의 ID를 새 session으로 바꾸지 않는다. 재개 설정은 이후 적용할 desired configuration이며 의미를 보존할 수 없는 변경을 거절한다. checkpoint 복원·Task 재접속·외부 효과의 복원은 포함하지 않는다. 영속성 지원 탐색과 보관 식별자의 구체적 API는 미확정이다.

재개 응답에서 제공 경계가 정규화한 식별자를 반환하면 새 handle에는 그 식별자를 사용한다. 입력 ID를 무조건 그대로 돌려주지 않는다. 이후 재개에 필요한 provider와 보관 식별자를 함께 보관하며, 다른 harness 인스턴스에서 같은 기본 SessionId만으로 동일한 영속 문맥을 추정하지 않는다.

## AgentTask

| 연산 / 관찰 | 계약 |
|---|---|
| `id: TaskId` | 한 번의 위임을 식별한다. native turn ID와 같을 필요가 없다. |
| `state` | `TaskState` snapshot. event collector가 없어도 갱신된다. |
| `events` | 진행·도구·효과·interaction·종결의 의미 이벤트. [Event contract](event-contract.md)를 따른다. |
| `pendingInteractions` | 현재 유효한 외부 응답 요청의 snapshot. 이벤트 유실과 독립이다. |
| `respond(id, response)` | 열린 요청에 일회 응답한다. 종류·값·ID를 검증한다. |
| `requestCancellation()` | 실제 중단을 요청한다. 반환이 중단 완료를 뜻하지 않는다. |
| `awaitOutcome()` | 모든 waiter가 같은 종결 판정을 회수한다. |

개념 예시이며 session 구성과 outcome의 최종 필드 배치는 생략했다.

```kotlin
val task = session.startTask(TaskInput.Text("요청을 검토하고 처리안을 작성해줘."))
val outcome = task.awaitOutcome()
```

Handle 생성 전의 검증·시작 실패는 호출 실패다. handle을 받은 이후 작업의 실패·취소·종료 미확정은 outcome으로 전달한다. waiter 자체의 coroutine 취소는 작업 중단을 의미하지 않는다.

시작 요청을 보낸 뒤 응답을 잃은 호출 실패는 작업이 실행되지 않았다는 증거가 아니다. 수락 여부를 모르는 경우를 확정적인 사전 거절과 구별해 전달하고, 같은 문맥의 다음 작업이나 재시도로 중복 실행하지 않는다. 전달 수락 여부와 작업 identity를 회수할 구체적 API는 [전환 계획](port-revision-plan.md)에서 확정한다.

## Interaction

요청 ID, 관련 작업 식별, caller에게 제시할 질문·행위 설명, 답변 제약은 공통 계약으로 표현한다. vendor 원본 `detail`을 해석해야 응답할 수 있는 구조를 필수 경로로 만들지 않는다.

| 요청 / 응답 | 의미 |
|---|---|
| `InteractionRequest.Approval` / 승인 결정 | 행위의 허용·거절 또는 작업 중단 요청. 허용 범위·기간을 명확히 한다. |
| `InteractionRequest.Question` / `InteractionResponse.Answer` | 진행에 필요한 정보를 묻고 답한다. 최초 입력이나 새 작업과 구별한다. |

요청이 열리면 `AWAITING_RESPONSE`이고 마지막 요청이 닫힌 뒤 작업이 계속되면 `RUNNING`으로 돌아간다. 하나에 답했다고 모든 요청이 해결된 것으로 보고하지 않는다.

필요한 interaction 지원은 작업 전에 요구·검증한다. 미지원 질문을 승인으로 위장하지 않는다. 중복·만료·잘못된 응답은 거절하며 provider가 그 사이 요청을 닫은 경쟁도 처리한다. 요청 취소·대체·작업 종결은 응답 없는 정리 사유로 구별한다.

`respond`의 성공 반환은 응답 전달·수락이 확인됐음을 뜻하며, 모든 요청의 해결이나 작업 재개 완료까지 뜻하지 않는다. 잘못된 종류·허용되지 않은 결정·다른 Task의 ID는 전달 전에 거절한다. 응답 후 acknowledgement를 잃었다면 확정적인 미전달로 보고 재전송하지 않는다. 요청 상태 확인이나 명시적인 중복 제거 없이 이중 응답·효과를 만들지 않는다.

## 상태와 종결 판정

| TaskState 목표 명칭 | 의미 / 대응 outcome |
|---|---|
| `STARTING` | 작업이 수락됐으나 실제 진행은 아직 확인하지 못했다. |
| `RUNNING` | 작업이 진행 중이다. |
| `AWAITING_RESPONSE` | 유효한 외부 응답을 기다린다. |
| `COMPLETED` | `Completed`: 실행 종료와 결과를 확인했다. |
| `FAILED` | `Failed`: 작업 실패를 확인했다. |
| `CANCELLED` | `Cancelled`: 취소에 의한 실제 종료를 확인했다. |
| `UNRESOLVED` | `Unresolved`: 관찰·제어를 종결하면서 실제 결과를 확인하지 못했다. |

뒤의 네 상태는 해당 handle의 종결이다. outcome은 정확히 한 번 확정하며 덮어쓰지 않는다. `UNRESOLVED`가 외부 작업 종료를 뜻하지 않는다는 점이 중요하다. [Lifecycle](lifecycle-and-concurrency.md)을 따른다.

어떤 outcome이든 `awaitOutcome()`이 반환하기 전에 대응 terminal state와 빈 pending snapshot이 확정돼 있어야 한다. 이미 해결된 요청은 그대로 두고, 열린 요청은 실제 원인에 맞춰 정리한다. 정리된 ID에 대한 늦은 응답은 거절한다. collector callback들이 그 갱신을 관찰하는 순서까지 보장하는 것은 아니다.

`Completed`도 반복 한도 등 종료 사유를 보존한다. 자연 종료와 한도·반복 감지·provider 중단을 구별하되 내부 step/turn을 보편적 업무 단위로 승격하지 않는다. 기존 `StopReason` 세부 enum의 전환은 결과 모델과 함께 확정한다.

확인된 실패에는 인증, 정책, 문맥 한도, 예산, 일시적 오류 등 후속 판단에 필요한 분류를 제공한다. 통신 오류만 관찰했다면 원격 작업의 실패·취소를 추정하지 않는다. 재시도 가능성과 이미 발생한 효과의 중복 위험은 별개다.

구체적인 실패 분류는 구조화된 provider code, 의미가 확인된 native 예외, 직접 확인한 runtime 사실에 근거한다. 자연어 오류 문구의 추측만으로 인증 실패·재시도 가능 등을 확정하지 않는다. 근거가 부족하면 알려진 설명과 미분류 상태를 보존한다.

## 산출물과 부가 관찰

`TaskOutput.Text`는 전달한 텍스트 산출물이다. 진행 설명으로 최종 산출물을 덮어쓰지 않는다. 부분 산출물이 있어도 outcome을 성공으로 바꾸지 않는다.

구조화 산출물을 요구한 경우 schema·검증 책임·검증 실패의 처리까지 선택 계약으로 정의한다. JSON 문자열 하나를 반환하는 것만으로 schema 지원을 선언하지 않는다. outcome에 산출물·종료 사유·사용량을 배치할 구체적 필드는 미확정이다.

공통 `UsageChanged`는 해당 Task의 누적 snapshot이며 이전 값에 더하지 않는다. provider의 증분·누적 표현 차이는 adapter가 번역한다. Session 누적은 실제 제공할 수 있을 때 별도로 전달한다. 모르는 값은 unknown이며 0으로 합성하지 않는다. context 관리 관찰은 문맥 상한 집행 보장이 아니다. `ProviderDiagnostic`은 별도 진단 경로이며 기본 결과나 실패 분류를 대신하지 않는다.

## 구현 전환

현재 코드의 `AgentExecution`, `AgentResult`, 기본 `resumeSession`, `ExecutionPolicy` 등의 이전 계약은 [이름 전환표](abstraction-and-terminology.md)와 [구현 계획](port-revision-plan.md)에 따라 바꾼다. KDoc·컴파일 가능한 예제는 실제 타입·동작과 함께 갱신한다. 새 계약이 현재 배포본에 이미 구현돼 있다고 해석하지 않는다.
