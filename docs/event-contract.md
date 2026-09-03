# Event contract

이 문서는 `AgentExecution.events`와 `AgentEvent` 전체의 소비 규칙을 정의한다. 타입 목록은 [protocol-reference.md](protocol-reference.md), 객체 수명은 [lifecycle-and-concurrency.md](lifecycle-and-concurrency.md)를 함께 참고한다.

## 가장 중요한 규칙

1. event stream은 한 `ExecutionId`에 속한 실시간 관찰 스트림이다.
2. 전달된 이벤트의 순서는 해당 adapter가 provider에서 관찰한 순서다.
3. 전달은 collector별 bounded queue다. 느린 collector는 non-terminal 이벤트를 잃을 수 있고, 잃었다면 `ObservationGap`으로 통보받는다. terminal 이벤트는 절대 잃지 않으며 항상 마지막이다. replay는 계약이 아니다.
4. lifecycle은 event 전달과 독립이다. collector가 없거나 멈춰 있어도 `state`와 `awaitResult()`는 진행한다. 완료 여부는 event 수신 여부가 아니라 그 둘로 판단한다.
5. `MessageDelta`는 append fragment지만 `MessageCompleted`는 완성된 message snapshot이다.
6. `UsageChanged`는 더할 delta가 아니라 누적 snapshot이며 `execution`과 `session` 두 범위를 따로 담는다.
7. 같은 provider 작업이 `ToolCallChanged`와 `EffectChanged`를 모두 만들 수 있다.
8. `ProviderEventObserved`는 payload 번역 손실을 피하는 escape hatch이지 delivery가 영속적이라는 뜻이 아니다.
9. `InteractionRequested`는 알림이고 `AgentExecution.pendingInteractions`가 authoritative snapshot이다.

## 전달, backpressure, replay

`events`의 정적 타입은 `Flow<AgentEvent>`다. adapter는 세 책임을 분리한다.

```text
provider/host ──(무손실 mailbox)──> execution actor ──> state, pendingInteractions, awaitResult  (authoritative)
                                         │
                                         └──> collector별 bounded queue ──> events              (observability)
```

- actor는 collector를 기다리지 않는다. 그래서 collector가 없거나 멈춰 있어도 terminal이 확정된다.
- collector의 queue(기본 256)가 넘치면 non-terminal 이벤트가 그 collector에 대해서만 버려지고, 다음에 전달되는 이벤트 앞에 `ObservationGap(droppedEvents)`가 온다. 다른 collector와 lifecycle은 영향을 받지 않는다.
- terminal 이벤트는 자리를 항상 확보하며 gap 뒤에 마지막으로 전달된다.
- 늦게 구독한 collector는 이전 이벤트를 받지 못한다. 이미 terminal이면 terminal 이벤트만 받는다.

`execute()`가 반환되기 전이나 collector가 시작되기 전에 provider가 초기 이벤트를 만들 수 있다. 그러므로 다음을 지킨다.

- 완전한 관찰이 필요하면 execution handle을 받은 직후 collector를 시작하고 빠르게 소비한다. 이것은 관찰 완전성을 위한 권장사항이지 lifecycle 정확성의 전제가 아니다.
- `ExecutionStarted`를 못 받았다고 시작되지 않았다고 판단하지 않는다.
- terminal event를 못 받았다고 실행이 끝나지 않았다고 판단하지 않는다.
- 정확한 결과와 실패는 `awaitResult()`로 받는다.
- 답해야 할 승인 요청은 `pendingInteractions`로 확인한다.
- 완전한 감사 로그가 필요하면 소비자가 받은 이벤트를 자체 저장하되, adapter가 관찰 전에 발생한 provider 이력까지 복원한다고 가정하지 않는다.

## 순서와 terminal 규칙

하나의 execution에서 전달되는 이벤트는 adapter가 관찰한 순서를 유지한다. 서로 다른 execution 사이에는 전역 순서가 없다.

정상적인 의미 순서는 다음과 같다.

```text
ExecutionStarted
    ├─ MessageDelta / MessageCompleted
    ├─ ReasoningDelta
    ├─ ToolCallChanged / EffectChanged
    ├─ ContextManaged
    ├─ UsageChanged
    ├─ Warning
    ├─ InteractionRequested ──> InteractionResolved
    ├─ ObservationGap (collector별)
    └─ exactly one semantic terminal event
         ├─ ExecutionCompleted
         ├─ ExecutionFailed
         └─ ExecutionCancelled
```

provider 오류나 빠른 취소 때문에 `ExecutionStarted` 없이 terminal outcome이 생길 수 있다. terminal outcome 뒤에는 해당 execution의 새로운 의미 이벤트가 없다. provider가 terminal과 같은 payload에서 만든 `ProviderEventObserved`는 terminal 앞에 전달되고, terminal 뒤에 도착한 provider 이벤트는 무시된다.

`state` 변경과 terminal event 전송은 하나의 atomic observation이 아니다. collector가 terminal event를 처리할 때 `state`가 이미 terminal일 수 있고, state collector와 event collector의 상대적인 callback 순서를 가정할 수 없다.

## 메시지 이벤트

### `MessageDelta`

하나의 agent-authored message에 추가할 exact fragment다.

```kotlin
when (event) {
    is AgentEvent.MessageDelta -> buffers
        .getOrPut(event.phase) { StringBuilder() }
        .append(event.text)
    else -> Unit
}
```

- `text`는 빈 문자열일 수 있다.
- `PROGRESS`는 commentary나 중간 설명이다.
- `FINAL`은 최종 답변을 구성하는 내용이다.
- provider에 따라 하나의 execution 안에서 여러 message stream이 있을 수 있으므로 모든 delta를 무조건 하나의 문자열로 합친 값이 최종 답이라고 가정하지 않는다.

### `MessageCompleted`

한 message의 완성된 canonical snapshot이다. 이전 `MessageDelta` 뒤에 다시 append하는 값이 아니다.

- `PROGRESS` completed message는 여러 개일 수 있다.
- `FINAL` completed message가 있더라도 execution 전체의 최종 canonical 값은 `AgentResult.finalMessage`다.
- provider가 completed-message event를 제공하지 않고 execution result만 제공할 수도 있으므로 성공 판단에 필수 이벤트로 취급하지 않는다.

### `ReasoningDelta`

provider가 노출한 reasoning text 또는 reasoning summary의 fragment다. 모든 provider가 같은 수준의 reasoning을 공개한다는 뜻이 아니며 숨겨진 chain-of-thought에 대한 계약도 아니다.

이 데이터는 진행 UI와 진단에 사용할 수 있지만 최종 답변, 보안 결정 또는 portable business rule의 입력으로 사용하지 않는다.

## 작업과 효과

`ToolCallChanged`와 `EffectChanged`는 서로 다른 관점이다.

| 이벤트 | 질문 | 안정성 |
|---|---|---|
| `ToolCallChanged` | agent가 어떤 이름의 tool 작업을 수행했는가? | tool 이름과 JSON schema는 provider 영향을 받는다. |
| `EffectChanged` | 외부 세계에 어떤 종류의 효과가 발생했는가? | command/file/search 등 portable 분류를 제공한다. |

예를 들어 `run_shell_command`라는 provider tool은 같은 `WorkId`를 가진 다음 두 이벤트를 만들 수 있다.

```text
ToolCallChanged(name="run_shell_command", status=STARTED)
EffectChanged(kind=COMMAND, status=STARTED)
...
ToolCallChanged(name="run_shell_command", status=COMPLETED)
EffectChanged(kind=COMMAND, status=COMPLETED, exitCode=0)
```

두 이벤트를 독립된 두 번의 실행으로 세지 않는다.

### Work lifecycle

```text
STARTED ──> UPDATED ──> UPDATED ──> COMPLETED
    │                              ├─> FAILED
    │                              ├─> DECLINED
    │                              └─> CANCELLED
    ├──────────────────────────────> COMPLETED
    ├──────────────────────────────> FAILED
    ├──────────────────────────────> DECLINED
    └──────────────────────────────> CANCELLED
```

- `UPDATED`는 없거나 여러 번 올 수 있다.
- 일부 provider는 시작 event 없이 completed snapshot만 제공할 수 있다.
- execution 자체가 취소되거나 transport가 끊기면 열린 work item의 terminal event가 없을 수 있다.
- `DECLINED`는 작업 자체의 실패가 아니라 approval 또는 policy로 실행되지 않았다는 뜻이다.
- `CANCELLED`는 execution 취소 때문에 작업이 완료 전에 중단되었다는 뜻이다.
- work item의 실패가 항상 execution 전체의 실패를 뜻하지는 않는다. agent가 오류를 처리하고 계속 진행할 수 있다.

### `ToolCallChanged`

- `workId`: 이 실행 안에서 lifecycle을 연결하는 opaque ID
- `name`: provider tool 이름
- `arguments`: provider JSON arguments, 알 수 없으면 `JsonNull`
- `result`: provider JSON result, 아직 없거나 알 수 없으면 `JsonNull`
- `error`: 가능한 경우 human-readable 오류

`arguments`와 `result`의 schema는 공통 계약이 아니다. portable 로직은 알려진 provider capability 안에서만 이를 해석한다.

### `EffectChanged`

- `kind`: `COMMAND`, `FILE_CHANGE`, `WEB_SEARCH`, `CONTEXT_MANAGEMENT`, `OTHER`
- `description`: command, query 또는 간략한 설명
- `output`: streaming fragment일 수도 있고 aggregate output일 수도 있으므로 같은 `WorkId`의 값을 무조건 연결하거나 교체하지 않는다. provider mapping 문서가 더 강한 규칙을 줄 때만 따른다.
- `exitCode`: command provider가 보고한 경우에만 존재한다.
- `changedPaths`: provider가 보고한 경로다. 빈 목록은 변경이 없었다는 증명이 아니다.

`EffectKind.CONTEXT_MANAGEMENT`는 일반 effect 분류가 필요한 확장을 위한 값이다. 현재 context 전용 의미는 `ContextManaged`가 우선한다.

## Context와 usage

### `ContextManaged`

provider가 compaction 또는 동등한 context 관리를 수행했음을 나타낸다.

- `beforeTokens`와 `afterTokens`가 `null`이면 관리가 없었다는 뜻이 아니라 provider가 수치를 제공하지 않았다는 뜻이다.
- 이 이벤트는 provider가 스스로 context를 관리했다는 관찰일 뿐이다. caller가 상한을 요청하는 정책은 0.1.0 포트에 없다([capability-candidates.md](capability-candidates.md)).

### `UsageChanged`

그 시점까지 알려진 usage의 누적 snapshot이다. 이전 값에 더하지 않는다.

- `execution`: 이 execution 안에서의 누적. 이전 execution의 사용량은 섞이지 않는다.
- `session`: provider가 session 단위 누적을 보고할 때만 있다. Codex는 thread 누적을 보고하고 Gemini CLI SDK는 보고하지 않는다.

```kotlin
var latestUsage: AgentUsage? = null

execution.events.collect { event ->
    if (event is AgentEvent.UsageChanged) {
        latestUsage = event.execution
    }
}
```

새 snapshot의 nullable 필드는 provider가 해당 값을 제공하지 않았다는 뜻이다. 최종적으로는 `AgentResult.usage`/`sessionUsage`를 우선한다.

## 경고

`Warning`은 execution을 그 자체로 종료하지 않는 advisory 또는 recoverable condition이다. `kind`로 분류된다.

| kind | 의미 |
|---|---|
| `CONTEXT_PRESSURE` | context가 한도에 가까워 compaction이나 실패가 뒤따를 수 있다. |
| `CONFIGURATION` | provider 설정 경고. `CALLER_DECIDES`가 아닌 정책에서 도착한 승인 요청을 adapter가 거절했을 때도 이 kind다. |
| `RECOVERABLE` | provider가 스스로 복구를 시도하는 오류 (예: Codex `error` with `willRetry`). |
| `OTHER` | 그 외 |

UI나 log에 표시할 수 있지만 warning 수신만으로 `cancel()`하거나 실패로 판정하지 않는다. 실제 결과는 terminal state가 결정한다. 한도 도달·loop 감지는 warning이 아니라 `AgentResult.stopReason`으로 전달된다.

## Interaction 이벤트

`ApprovalPolicy.CALLER_DECIDES`에서 provider가 승인을 요청하면 다음 순서가 된다.

```text
InteractionRequested(request)          state → WAITING, pendingInteractions += request
    │
    ├─ caller: execution.respond(id, response)
    │       └─> InteractionResolved(id, Responded(response))
    └─ provider/adapter cleanup
            └─> InteractionResolved(id, Cleared(reason))
                                       pendingInteractions -= request; 비면 state → RUNNING
```

- 여러 request가 동시에 열릴 수 있다. 모두 닫혀야 `RUNNING`으로 돌아간다.
- `InteractionRequested`는 느린 collector에서 drop될 수 있는 일반 이벤트다. 답해야 할 request의 진실은 `pendingInteractions`다.
- terminal이 확정되면 열린 request는 모두 `Cleared(TURN_INTERRUPTED)`로 닫히고 snapshot이 비워진다.
- `cancel()`은 열린 request를 먼저 정리한 뒤 provider를 중단한다.

## Terminal 이벤트와 결과

### `ExecutionCompleted`

성공 terminal event다. `result`는 `awaitResult()`가 반환하는 것과 같은 의미다. 이벤트 객체의 동일성까지 보장하는 것은 아니다.

`result.stopReason`이 `FINISHED`가 아니면 agent가 스스로 끝낸 것이 아니라 provider 한도(`TURN_LIMIT`), loop 감지(`LOOP_DETECTED`), 그 밖의 provider 중단(`PROVIDER_STOPPED`)이다. 이것은 실패가 아니다. 대화는 정상 상태로 남아 있고 다음 `execute`로 이어갈 수 있다.

### `ExecutionFailed`

실패 terminal event다. `kind`는 portable 분류(`FailureKind`)이고 `message`는 사람이 읽는 설명이다. `awaitResult()`는 같은 `kind`를 가진 `AgentExecutionFailedException`을 던진다. adapter는 provider의 구조화된 error code로만 분류하고 free text 휴리스틱으로 `AUTHENTICATION`·`TRANSIENT`를 단정하지 않는다. 분류 불가면 `PROVIDER` 또는 `UNKNOWN`이다.

### `ExecutionCancelled`

취소 terminal event다. 누가 취소했는지는 표현하지 않는다. `awaitResult()`는 `AgentExecutionCancelledException`을 던진다. harness `close()`도 진행 중 execution을 이 outcome으로 확정한다. host process가 예기치 않게 죽으면 대신 `ExecutionFailed(TRANSPORT)`다.

취소 요청과 자연 완료가 경쟁하면 provider가 먼저 확정한 terminal outcome이 승리한다. 따라서 `cancel()` 호출 후에도 `COMPLETED`가 될 수 있다.

## `ProviderEventObserved`

공통 이벤트로 번역하면서 버리게 되는 provider-specific 정보를 관찰하기 위한 escape hatch다.

- `provider`: 원본 provider
- `name`: provider method 또는 event type
- `payload`: provider-specific JSON body

사용 가능한 용도:

- 진단 log
- 새로운 공통 추상 후보 조사
- provider adapter 회귀 테스트
- 특정 provider만을 대상으로 한 선택 capability

피해야 할 용도:

- provider-neutral 핵심 business logic
- 장기 저장 후 schema가 변하지 않을 것이라는 가정
- 모든 event가 영구적으로 replay된다는 가정
- payload에 credential이나 민감 데이터가 절대 없다는 가정

저장하거나 외부 telemetry로 보낼 때는 provider payload의 민감 정보 정책을 별도로 적용한다.

## 권장 소비 패턴

진행 관찰과 결과 대기를 sibling coroutine으로 관리한다.

```kotlin
coroutineScope {
    val execution = session.execute(AgentInput.Text("Implement the change."))

    val observer = launch {
        execution.events.collect { event ->
            when (event) {
                is AgentEvent.MessageDelta -> renderDelta(event)
                is AgentEvent.EffectChanged -> renderEffect(event)
                is AgentEvent.Warning -> renderWarning(event.message)
                else -> Unit
            }
        }
    }

    try {
        val result = execution.awaitResult()
        renderFinal(result.finalMessage, result.stopReason)
    } catch (cancelled: AgentExecutionCancelledException) {
        renderCancelled()
    } catch (failure: AgentExecutionFailedException) {
        renderFailure(failure.kind, failure.message ?: "Agent execution failed")
    } finally {
        observer.cancel()
    }
}
```

이 패턴은 event collector가 terminal event를 놓치거나 event flow가 스스로 완료되지 않더라도 observer를 정리한다.

## Adapter 구현 체크리스트

- 모든 공통 event에 올바른 `ExecutionId`를 붙인다.
- provider가 준 work ID를 가능하면 보존하고 tool/effect에 같은 ID를 쓴다.
- delta와 completed snapshot을 혼동하지 않는다. 최종 답변 phase의 completed message만 `finalMessage`로 채택한다.
- usage를 delta가 아닌 execution 누적 snapshot으로 변환하고, session 누적은 provider가 줄 때만 채운다.
- provider terminal outcome 하나만 공통 terminal event로 만들고 terminal 뒤 이벤트는 버린다.
- terminal 상태와 `awaitResult()` outcome, 예외 타입을 일치시킨다.
- 실패는 구조화된 provider code로만 `FailureKind`를 분류한다.
- 한도·loop 중단은 `stopReason`으로, 복구 가능 오류는 `Warning(RECOVERABLE)`로 낸다.
- 번역하지 못한 provider event를 `ProviderEventObserved`에 보존한다.
- lifecycle 갱신을 collector backpressure에 종속시키지 않는다. drop은 `ObservationGap`으로만 일어난다.
