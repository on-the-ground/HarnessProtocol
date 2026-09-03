# Lifecycle and concurrency

이 문서는 `AgentHarness`, `AgentSession`, `AgentExecution`의 소유권, 상태 전이, 취소, close와 coroutine 사용 규칙을 정의한다. API 필드 설명은 [protocol-reference.md](protocol-reference.md), 이벤트 의미는 [event-contract.md](event-contract.md)를 참고한다.

## 소유권 트리

```text
application scope
└─ AgentHarness                       provider runtime/transport owner
   ├─ AgentSession A                  durable conversation
   │  ├─ AgentExecution A-1           one completed agent loop
   │  └─ AgentExecution A-2           next sequential loop
   └─ AgentSession B                  logically isolated conversation
      └─ AgentExecution B-1
```

핵심 규칙은 다음과 같다.

- harness가 session과 execution의 runtime 수명을 소유한다.
- session은 `release()`로 자기 runtime handle을 일찍 놓을 수 있다. provider의 durable conversation은 남는다.
- harness를 닫은 뒤 그 아래 handle을 사용하지 않는다.
- 같은 session에서는 execution을 순차적으로 실행한다. adapter가 이를 강제한다.
- 서로 다른 session은 논리적으로 격리되지만 adapter가 실제 provider 작업을 직렬화할 수 있다.

## Harness lifecycle

```text
construct ──> usable ── create/resume/execute ──> close ──> unusable
```

adapter factory가 harness 객체를 만들었다고 provider process가 즉시 시작됐다는 보장은 없다. 구현체는 첫 request 때 runtime을 lazy-start할 수 있다.

권장 소유 방식:

```kotlin
Harnesses.codex().use { harness ->
    val session = harness.createSession(spec)
    val result = session.execute(AgentInput.Text("Do the work.")).awaitResult()
}
```

`close()`는 다음 순서로 정리한다.

1. 모든 session의 진행 중 execution에 `cancel()`을 요청하고 짧은 유예(기본 2초) 동안 terminal을 기다린다. 유예가 지나면 `CANCELLED`로 확정한다. 열린 interaction은 `Cleared(TURN_INTERRUPTED)`로 닫힌다.
2. provider SDK client, child process, transport, 내부 coroutine scope를 닫는다.

즉 명시적 close는 execution을 **`CANCELLED`** 로 끝낸다. 반면 host process가 예기치 않게 죽으면 모든 진행 중 execution은 **`FAILED(TRANSPORT)`** 로 끝나고, 그 harness는 이후 모든 요청에 `HarnessTransportException`을 던진다. host를 다시 시작하지 않으므로 새 harness를 열어야 한다.

close 중이거나 close 이후 시작한 작업의 세부 예외는 adapter에 따라 다를 수 있다. portable 코드는 close와 새 create/resume/execute를 경쟁시키지 않는다.

## Session 생성

### 새 session

```text
AgentSpec
   │
   ├─ validate ── incompatible ──> IncompatibleAgentSpecException
   │
   └─ createSession ─────────────> AgentSession(id, spec)
```

`validate`는 선택적인 사전 진단이고 `createSession`은 실제 경계에서 다시 검증한다. session 생성 호출은 provider가 durable conversation을 만들고 ID를 반환할 때까지 suspend할 수 있다.

### 기존 session resume

resume에 필요한 저장 정보는 최소한 다음 두 값이다.

```kotlin
data class StoredConversation(
    val provider: String,
    val sessionId: String,
)
```

```kotlin
val harness = Harnesses.create(stored.provider)
val session = harness.resumeSession(SessionId(stored.sessionId), spec)
```

`SessionId`만 저장하면 어느 adapter가 소유한 ID인지 알 수 없다.

resume 시 전달하는 `AgentSpec`은 과거 설정의 증명이 아니라 **resume 이후 보존해야 할 desired configuration**이다. provider는 resume 시 일부 설정(모델, cwd, sandbox 등)의 override를 공식적으로 허용하므로 adapter는 지원하는 override를 적용하고, 변경할 수 없거나 의미를 보존할 수 없는 필드만 path별 `ERROR`로 거절한다. adapter는 spec hash를 저장하거나 host 메모리를 진실의 원천으로 삼지 않는다. provider가 ID를 정규화해 돌려주면 `AgentSession.id`는 그 값이다.

### Session release

```kotlin
val session = harness.createSession(spec)
try {
    session.execute(AgentInput.Text("Do the work.")).awaitResult()
} finally {
    session.release()   // idempotent; provider conversation은 남는다
}
```

서버형 애플리케이션이 하나의 harness로 많은 대화를 다룰 때 `release()`가 없으면 host의 session 항목이 harness 수명 동안 쌓인다. release는 진행 중 execution을 먼저 취소하고 bounded wait 뒤 host handle을 해제한다. release 뒤 `execute`는 `IllegalStateException`이며, 같은 ID로 `resumeSession`하면 새 handle을 얻는다.

## Session 안의 실행 순서

한 session은 대화 문맥을 공유하므로 다음 순서를 따른다.

```text
execute(input 1) ──> execution 1 terminal
                                      │
                                      └─> execute(input 2) ──> execution 2 terminal
```

안전한 코드:

```kotlin
val firstResult = session
    .execute(AgentInput.Text("Analyze the problem."))
    .awaitResult()

val secondResult = session
    .execute(AgentInput.Text("Implement the proposed solution."))
    .awaitResult()
```

금지되는 portable 가정:

```kotlin
// 같은 conversation에 두 turn을 겹쳐 보내므로 portable하지 않다.
coroutineScope {
    launch { session.execute(AgentInput.Text("Task A")) }
    launch { session.execute(AgentInput.Text("Task B")) }
}
```

adapter는 이 규칙을 강제한다. 이전 execution이 terminal이 되기 전의 `execute`는 provider에 요청을 보내지 않고 `IllegalStateException`을 던진다. `cancel()`만 요청한 상태는 아직 terminal이 아니므로 역시 거절된다.

## 서로 다른 session의 동시성

서로 다른 session은 논리적으로 독립되어 있으므로 애플리케이션은 동시에 사용할 수 있다.

```kotlin
coroutineScope {
    val sessionA = harness.createSession(specA)
    val sessionB = harness.createSession(specB)

    val a = async { sessionA.execute(AgentInput.Text("Task A")).awaitResult() }
    val b = async { sessionB.execute(AgentInput.Text("Task B")).awaitResult() }

    consume(a.await(), b.await())
}
```

그러나 이 코드는 병렬 provider 처리량을 보장하지 않는다. adapter는 단일 SDK host, process 제한, rate limit 또는 provider session 모델 때문에 내부적으로 직렬화할 수 있다. 중요한 계약은 한 session의 context가 다른 session과 섞이지 않는다는 점이다.

## Execution lifecycle

### 시작

`AgentSession.execute()`는 전체 agent loop를 기다리는 함수가 아니다.

```text
execute called
    │ provider accepts request
    ▼
AgentExecution returned (state = STARTING or already later)
    │
    ├─ events observed
    └─ awaitResult waits for terminal outcome
```

provider가 매우 빨리 진행하면 handle을 받는 시점에 state가 이미 `RUNNING` 또는 terminal일 수 있다. 초기 상태를 반드시 직접 관찰할 수 있다고 가정하지 않는다.

### 상태 전이

```text
                 ┌──────────────────────────┐
                 ▼                          │ 마지막 request가 닫힘
STARTING ──> RUNNING ── request 발생 ──> WAITING
    │           ├──> COMPLETED             ├──> COMPLETED
    │           ├──> FAILED                ├──> FAILED
    │           └──> CANCELLED             └──> CANCELLED
    ├──> FAILED
    └──> CANCELLED
```

- `COMPLETED`, `FAILED`, `CANCELLED`는 terminal이다.
- terminal 상태는 다른 상태로 전이하지 않는다.
- 빠른 실패나 취소는 `RUNNING`을 건너뛸 수 있다.
- `WAITING`은 `ApprovalPolicy.CALLER_DECIDES`에서 provider가 승인을 기다릴 때다. 아래 "Interaction" 절을 참고한다.
- state는 execution actor가 갱신하며 event 전달과 독립이다. state collector와 event collector 사이의 순서를 가정하지 않는다.

### 결과 대기

`awaitResult()`는 completion synchronization 지점이다.

- 성공: `AgentResult` 반환. `stopReason`으로 agent가 스스로 끝냈는지 확인한다.
- 실패: `AgentExecutionFailedException(kind)` throw
- 취소: `AgentExecutionCancelledException` throw
- 여러 waiter: 같은 terminal outcome 관찰

coroutine이 `awaitResult()`를 기다리다 자체적으로 취소되면 그 waiter는 취소된다. 이것만으로 provider execution도 자동 취소된다고 가정하지 않는다. provider 작업도 중단하려면 명시적으로 `execution.cancel()`을 호출한다.

## 취소

취소에는 요청과 확정의 두 단계가 있다.

```text
cancel() ── request accepted ──> returns
                                   │
                                   └─ later terminal outcome
                                        ├─ CANCELLED
                                        ├─ FAILED
                                        └─ COMPLETED (race won by completion)
```

따라서 다음처럼 처리한다.

```kotlin
execution.cancel()

try {
    val result = execution.awaitResult()   // 경쟁에서 완료가 이겼다
} catch (cancelled: AgentExecutionCancelledException) {
    handleCancellation()
} catch (failed: AgentExecutionFailedException) {
    handleFailure(failed.kind, failed)
}
```

규칙:

- terminal 이후 `cancel()`은 no-op이다.
- `WAITING` 중의 `cancel()`은 열린 interaction을 먼저 `Cleared(TURN_INTERRUPTED)`로 닫고 provider를 중단한다.
- 반환은 terminal 도달을 뜻하지 않는다.
- terminal 이전의 반복·동시 cancel 요청은 추가 효과를 기대하지 않는다.
- cancel 요청과 자연 완료가 경쟁하면 이미 확정된 terminal outcome을 바꾸지 않는다.

## Interaction

`ApprovalPolicy.CALLER_DECIDES`로 연 session의 execution은 provider가 승인을 요구할 때 `WAITING`이 된다. 답하지 않으면 그 상태에 머문다. timeout은 애플리케이션 책임이다.

```kotlin
suspend fun runWithApprovals(execution: AgentExecution, decide: suspend (InteractionRequest.Approval) -> ApprovalDecision) = coroutineScope {
    val answering = launch {
        execution.pendingInteractions.collect { pending ->
            for (request in pending) {
                if (request is InteractionRequest.Approval && request.interactionId !in answered) {
                    answered += request.interactionId
                    val decision = withTimeoutOrNull(60_000) { decide(request) } ?: ApprovalDecision.DECLINE
                    runCatching { execution.respond(request.interactionId, InteractionResponse.Approval(decision)) }
                }
            }
        }
    }
    try {
        execution.awaitResult()
    } finally {
        answering.cancel()
    }
}
```

- `pendingInteractions`를 관찰하는 것이 event를 관찰하는 것보다 안전하다. 이벤트는 느린 collector에서 drop될 수 있지만 snapshot은 항상 정확하다.
- `respond`는 이미 닫힌 request에 대해 `IllegalStateException`을 던진다. provider가 그 사이 request를 정리했을 수 있으므로 위 예처럼 `runCatching`으로 감싼다.
- 한 request에 두 번 답할 수 없다. 두 번째는 `IllegalStateException`이다.
- harness `close()`와 session `release()`는 열린 request를 먼저 정리한 뒤 execution을 취소한다.

## Event collector lifecycle

event collector는 execution 결과 waiter와 함께 구조화된 scope 안에 둔다.

```kotlin
suspend fun observe(execution: AgentExecution): AgentResult = coroutineScope {
    val collector = launch {
        execution.events.collect(::recordEvent)
    }

    try {
        execution.awaitResult()
    } finally {
        collector.cancelAndJoin()
    }
}
```

현재 구현의 event flow는 terminal event 뒤 complete되지만 그것을 계약으로 삼지 않는다. 결과를 받은 뒤 collector를 명시적으로 정리하면 adapter별 stream 구현 차이에 안전하다.

동일 event flow에 여러 collector를 붙일 수 있다. 각 collector는 자기 bounded queue를 가지므로 한 collector가 느려도 다른 collector와 lifecycle에는 영향이 없다. 느린 collector는 `ObservationGap`을 받는다. logging, UI, metric이 모두 필요하다면 애플리케이션의 한 collector에서 자체 fan-out하는 방법이 가장 예측 가능하다.

## 오류가 발생하는 경계

오류 시점은 handle 생성 전과 후로 나뉜다.

```text
before AgentExecution exists                 after AgentExecution exists
────────────────────────────                 ────────────────────────────
invalid value                                provider turn failure        → FAILED, AgentExecutionFailedException(kind)
incompatible AgentSpec                       cancellation / close         → CANCELLED, AgentExecutionCancelledException
runtime/process startup failure              host death / stream failure  → FAILED, AgentExecutionFailedException(TRANSPORT)
start request transport failure
overlapping execute / released session

IllegalArgumentException
IncompatibleAgentSpecException
HarnessTransportException
IllegalStateException
```

`execute()`가 throw하면 execution handle이 없으므로 event나 state를 기다리지 않는다. handle을 받은 뒤의 terminal outcome은 `awaitResult()`로 회수해야 coroutine에 미관찰 실패를 남기지 않는다.

## Thread-safety와 공유

공개 포트가 보장하는 공유 규칙은 제한적이다.

- `StateFlow`는 여러 coroutine에서 안전하게 관찰할 수 있다.
- `events`는 여러 collector를 허용하는지와 replay 범위를 구체화하지 않는다.
- 같은 session에서 `execute`를 겹치지 않는다.
- 다른 session은 동시에 사용할 수 있지만 실제 병렬성은 adapter가 결정한다.
- harness close를 다른 작업 시작과 경쟁시키지 않는다.
- `AgentSpec`과 이벤트/결과 모델은 immutable value다.

adapter 구현자는 내부 mutable 상태와 provider request routing을 동기화하고, 서로 다른 execution의 event가 섞이지 않도록 해야 한다.

## 애플리케이션 scope와 timeout

Protocol은 자체 timeout 모델을 제공하지 않는다. 애플리케이션은 coroutine timeout을 사용할 수 있지만 timeout과 provider 취소를 함께 처리해야 한다.

```kotlin
val execution = session.execute(AgentInput.Text("Do bounded work."))

try {
    withTimeout(60_000) {
        execution.awaitResult()
    }
} catch (timeout: TimeoutCancellationException) {
    execution.cancel()
    throw timeout
}
```

timeout coroutine이 취소됐다고 provider 요청이 자동 중단된다고 가정하지 않는다. 필요하다면 별도 non-cancelled cleanup context에서 취소 요청과 제한된 종료 대기를 수행한다.

## Adapter 구현 체크리스트

- `createSession`과 `resumeSession`에서 `validate(spec).requireCompatible()`와 동등한 검증을 수행한다.
- resume 응답의 정규화된 session ID를 사용한다.
- 같은 session의 overlapping execution을 atomic gate로 거절하고 provider에 요청을 보내지 않는다.
- execution별 state가 단조롭게 terminal로 전이되게 하고, terminal은 정확히 한 번 확정한다.
- lifecycle 갱신을 public event collector에 종속시키지 않는다.
- `awaitResult()`의 모든 waiter에게 같은 결과 또는 실패를 전달한다.
- cancel 요청을 terminal completion과 race-safe하게 처리하고, 열린 interaction을 먼저 정리한다.
- harness close 시 진행 중 execution을 `CANCELLED`로, host death 시 `FAILED(TRANSPORT)`로 확정한다.
- session release가 host의 session 항목을 제거하게 한다.
- 서로 다른 execution의 event routing을 `ExecutionId`로 격리한다.

## 소비자 체크리스트

- harness는 `use` 또는 애플리케이션 수명 주기에 맞춰 닫는다.
- 오래 사는 harness에서는 다 쓴 session을 `release()`한다.
- provider와 session ID를 함께 저장한다.
- 같은 session의 실행은 순차적으로 기다린다.
- events는 즉시 수집하되 완료 판단에는 사용하지 않는다. `ObservationGap`을 처리한다.
- `CALLER_DECIDES`를 쓰면 `pendingInteractions`를 관찰하고 timeout을 스스로 정한다.
- 모든 execution에서 `awaitResult()`를 호출하거나 명시적으로 결과를 회수하고, `stopReason`으로 이어갈지 결정한다.
- coroutine timeout 시 provider `cancel()`도 고려한다.
- harness close와 새 작업 시작을 경쟁시키지 않는다.
