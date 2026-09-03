# Harness Protocol reference

이 문서는 Harness Protocol을 처음 사용하는 소비자를 위한 공개 API 레퍼런스다. 인터페이스가 표현하는 목적, 각 모델 필드의 의미, 오류 처리와 올바른 사용법을 설명한다. 이벤트 전달 규칙은 [event-contract.md](event-contract.md), 객체 수명과 동시성은 [lifecycle-and-concurrency.md](lifecycle-and-concurrency.md)에서 더 자세히 다룬다.

## 한 문장으로 이해하기

Harness Protocol은 서로 다른 agent SDK를 다음 공통 목적을 가진 Kotlin 포트로 다룬다.

```text
AgentHarness ── create/resume ──> AgentSession ── execute ──> AgentExecution
     runtime                         conversation                  one agent loop
                                       │ release                     │
                                                                     ├─ state
                                                                     ├─ events
                                                                     ├─ pendingInteractions / respond
                                                                     ├─ cancel
                                                                     └─ result
```

- `AgentHarness`는 provider runtime의 진입점이자 소유자다.
- `AgentSession`은 여러 실행 사이에서 문맥을 유지하는 대화다.
- `AgentExecution`은 하나의 입력을 처리하는 한 번의 완결된 agent loop다. loop는 caller의 승인을 기다리며 멈출 수 있다.
- `AgentSpec`은 SDK 생성 옵션이 아니라 호출자가 원하는 의미를 선언한다.
- adapter는 그 의미를 보존할 수 없으면 실행 전에 거절해야 한다.

포트에는 vendor의 `thread`, `turn`, `sendStream` 같은 구현 수단이 나타나지 않는다. 소비 코드는 어떤 provider를 선택했는지와 무관하게 대화를 만들고, 입력을 실행하고, 진행과 효과를 관찰하고, 승인에 답하고, 취소하고, 결과를 받을 수 있다.

## 최소 사용 예

```kotlin
import dev.harnessprotocol.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

suspend fun runAgent() = coroutineScope {
    val harness = Harnesses.codex()

    harness.use {
        val spec = AgentSpec(
            instructions = "Work carefully and explain the result.",
            workingDirectory = System.getProperty("user.dir"),
            executionPolicy = ExecutionPolicy(
                filesystem = FilesystemAccess.WorkspaceWrite(),
                network = NetworkAccess.DENIED,
                approval = ApprovalPolicy.AGENT_REVIEWED,
            ),
        )

        // 선택적 사전 진단. createSession도 같은 검증을 수행한다.
        harness.validate(spec).requireCompatible()

        val session = harness.createSession(spec)
        val execution = session.execute(AgentInput.Text("Run the tests and fix failures."))

        val observer = launch {
            execution.events.collect { event -> println(event) }
        }

        try {
            val result = execution.awaitResult()
            println("${result.stopReason}: ${result.finalMessage}")
        } catch (cancelled: AgentExecutionCancelledException) {
            println("cancelled")
        } catch (failed: AgentExecutionFailedException) {
            println("failed (${failed.kind}): ${failed.message}")
        } finally {
            observer.cancel()
        }
    }
}
```

`events`는 영속 이력이 아닌 실시간 관찰 스트림이다. 최종 성공 여부와 결과는 반드시 `state`와 `awaitResult()`를 기준으로 판단한다.

## `AgentHarness`

`AgentHarness`는 하나의 provider adapter와 그 runtime 자원을 나타낸다.

```kotlin
interface AgentHarness : AutoCloseable {
    val provider: ProviderId
    fun validate(spec: AgentSpec): CompatibilityReport
    suspend fun createSession(spec: AgentSpec): AgentSession
    suspend fun resumeSession(id: SessionId, spec: AgentSpec): AgentSession
}
```

### `provider`

구현체의 안정적인 식별자다. 현재 bundle factory가 사용하는 대표 값은 `codex`, `gemini-cli`다. UI 표시명이 아니며 설정과 adapter 선택에 사용한다.

### `validate(spec)`

adapter가 `AgentSpec`의 의미를 실제로 보존할 수 있는지 검사한다.

- `ERROR`가 하나라도 있으면 `isCompatible == false`다.
- `WARNING`만 있으면 실행할 수 있다. `WARNING`은 요청한 의미가 그대로 지켜지면서 부가 정보만 손실될 때만 쓴다.
- 지원되지 않는 정책을 provider default로 낮추는 것은 허용되지 않는다.

`validate`는 소비자가 오류를 미리 표시하기 위한 API다. 호출하지 않더라도 `createSession`과 `resumeSession`은 호환되지 않는 spec을 다시 검사하고 `IncompatibleAgentSpecException`을 던져야 한다.

### `createSession(spec)`

새로운 durable conversation을 만든다. 반환 시 session이 생성된 것이며 agent loop까지 끝난 것은 아니다. 반환된 `AgentSession.spec`은 생성에 사용한 의미 설정의 snapshot이다.

### `resumeSession(id, spec)`

기존 provider conversation을 다시 연다. `SessionId`는 provider별 opaque 값이므로 ID와 `ProviderId`를 함께 보관해야 한다. 다른 provider의 ID나 존재하지 않는 ID를 넘겼을 때 새 session으로 대체해서는 안 된다.

`spec`은 과거 설정의 증명이 아니라 **resume 이후 보존해야 할 desired configuration**이다. adapter는 provider가 허용하는 override를 적용하고, 변경할 수 없거나 의미를 보존할 수 없는 필드만 path별 `ERROR`로 거절한다. provider가 ID를 정규화해 돌려주면 반환된 `AgentSession.id`가 그 값이다.

### `close()`

runtime, transport 및 그 harness가 소유한 session/execution 자원을 닫는다. 진행 중인 execution은 취소 요청과 짧은 유예 뒤 `CANCELLED`로 확정된다. `use`로 관리하는 것이 권장된다. 닫힌 harness에서 얻은 handle은 다시 사용하지 않는다.

## `AgentSession`

`AgentSession`은 여러 agent loop 사이에서 대화 문맥을 유지한다.

```kotlin
interface AgentSession {
    val id: SessionId
    val spec: AgentSpec
    suspend fun execute(input: AgentInput): AgentExecution
    suspend fun release()
}
```

- `id`는 나중에 같은 provider의 `resumeSession`에 넘길 수 있는 conversation ID다.
- `spec`은 session을 열 때 사용한 설정이다.
- `execute`는 provider가 실행을 받아들여 live handle을 만들 때까지만 기다린다.
- 같은 session의 실행은 순차적이다. 이전 실행이 terminal이 되기 전의 `execute`는 adapter가 `IllegalStateException`으로 거절하며 provider에 요청을 보내지 않는다.
- `release()`는 이 harness 안의 session handle과 host 자원을 해제한다. 진행 중인 execution은 먼저 취소된다. provider의 durable conversation은 남으며 같은 `id`로 `resumeSession`할 수 있다. idempotent이고, release 뒤 `execute`는 `IllegalStateException`이다.

같은 session에서 다음 입력을 실행하면 이전 대화의 문맥을 이어간다.

```kotlin
val first = session.execute(AgentInput.Text("Inspect the failing tests."))
first.awaitResult()

val second = session.execute(AgentInput.Text("Now implement the fix."))
val result = second.awaitResult()
```

## `AgentExecution`

`AgentExecution`은 하나의 입력으로 시작된 한 번의 agent loop다.

```kotlin
interface AgentExecution {
    val id: ExecutionId
    val sessionId: SessionId
    val state: StateFlow<ExecutionState>
    val events: Flow<AgentEvent>
    val pendingInteractions: StateFlow<List<InteractionRequest>>
    suspend fun respond(interactionId: InteractionId, response: InteractionResponse)
    suspend fun cancel()
    suspend fun awaitResult(): AgentResult
}
```

### `state`

현재 lifecycle의 authoritative snapshot이다. 상태는 terminal 상태에 도달한 뒤 다시 바뀌지 않는다.

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

- provider가 시작 알림 전에 종료될 수 있으므로 `STARTING`에서 terminal 상태로 직접 이동할 수 있다.
- `WAITING`은 하나 이상의 `InteractionRequest`가 열려 있다는 뜻이다. 마지막 request가 닫히면 `RUNNING`으로 돌아간다.

### `events`

메시지 delta, reasoning, tool 작업, 외부 효과, context 관리, usage, interaction, terminal 결과를 실행별 순서로 관찰한다. collector마다 bounded queue로 전달되며, 느린 collector는 `ObservationGap`으로 손실을 통보받는다. terminal 이벤트는 항상 마지막에 전달된다. 늦게 구독한 collector가 이전 이벤트를 받는다고 가정하지 않는다.

상세 계약은 [event-contract.md](event-contract.md)를 참고한다.

### `pendingInteractions`

현재 caller의 답을 기다리는 request의 authoritative snapshot이다. `state`가 `WAITING`이 아니면 비어 있다. 이벤트를 놓친 collector도 이 snapshot으로 무엇에 답해야 하는지 알 수 있다.

### `respond(interactionId, response)`

열린 request에 답한다. 반환은 provider가 답을 받았다는 뜻이고, 다른 request가 남아 있으면 `WAITING`이 유지된다.

- 알 수 없거나 이미 닫힌 ID → `IllegalStateException`
- request 종류와 맞지 않는 응답, request가 제시하지 않은 decision → `IllegalArgumentException`
- 전달 실패 → `HarnessTransportException`

### `cancel()`

실행 중단을 요청한다. 열린 interaction은 `ClearReason.TURN_INTERRUPTED`로 먼저 정리된 뒤 provider가 중단된다. 함수가 반환되었다고 실행이 이미 종료된 것은 아니다. 이후 `state`가 `CANCELLED`, `FAILED`, 또는 경쟁에서 먼저 완료된 `COMPLETED`로 가는지 관찰하거나 `awaitResult()`를 기다린다.

terminal 상태에서 호출하면 no-op이다.

### `awaitResult()`

성공하면 `AgentResult`를 반환한다. 취소되면 `AgentExecutionCancelledException`, 실패하면 `AgentExecutionFailedException`(`kind` 포함)을 던진다. 여러 coroutine이 같은 execution의 결과를 기다릴 수 있으며 각각 동일한 terminal outcome을 관찰한다.

## `AgentSpec`

`AgentSpec`은 session 전체에 적용하려는 의미다.

| 필드 | 의미 | 기본값의 의미 |
|---|---|---|
| `instructions` | 지속적인 system/developer 수준 지시 | `null`: provider 기본 지시 |
| `model` | provider가 이해하는 정확한 모델 ID | `null`: provider 기본 모델 |
| `workingDirectory` | agent 도구가 기준으로 삼는 작업 디렉터리 | `null`: adapter/provider 기본 위치 |
| `skills` | 모든 실행에서 제공하고 명시적으로 활성화할 skill | 빈 목록: 추가 skill 없음 |
| `executionPolicy` | filesystem, network, approval 의도 | 각 provider default |

`null`은 빈 문자열과 다르다. 예를 들어 `instructions = null`은 provider 기본을 요청하지만 `instructions = ""`는 빈 지시를 명시적으로 전달하려는 값이다. adapter는 둘을 구분해 전달하며, 구분하지 못하면서 그 차이가 의미에 영향을 준다면 validation error를 반환해야 한다.

경로 문자열은 현재 protocol이 정규화하지 않는다. 다른 process에서 SDK가 실행될 수 있으므로 `workingDirectory`, skill path, writable root에는 절대 경로를 권장한다.

context 크기 관리 정책과 correlation metadata는 0.1.0 포트에 없다. 이유와 재도입 조건은 [capability-candidates.md](capability-candidates.md)에 있다.

### `SkillReference`

```kotlin
SkillReference(
    name = "release-check",
    path = "C:/agent-skills/release-check",
)
```

- `name`은 provider에 노출하고 활성화할 non-blank 이름이다.
- `path`는 skill 디렉터리의 non-blank 경로다.
- 생성 시 빈 값이나 공백뿐인 값은 `IllegalArgumentException`을 발생시킨다.
- 존재 여부와 provider 형식 검사는 adapter/runtime 단계에서 이뤄질 수 있다.

skill 활성화는 provider가 정한 activation envelope(예: `$name` mention과 skill input item)을 통해 이뤄진다. adapter는 그 envelope를 사용자 text 앞에 붙일 수 있지만 사용자 text 자체를 바꾸지 않는다.

## 실행 정책

### `FilesystemAccess`

| 값 | 요청 의미 |
|---|---|
| `ProviderDefault` | 현재 adapter/provider의 filesystem 정책을 변경하지 않는다. |
| `ReadOnly` | 읽기는 허용하지만 filesystem mutation은 막는다. |
| `WorkspaceWrite` | workspace와 `additionalWritableRoots` 안의 쓰기를 허용한다. |
| `FullAccess` | harness가 정의하는 filesystem sandbox 없이 실행한다. |

`ProviderDefault`는 provider 간 동일한 권한을 뜻하지 않는다. 동일한 권한이 필요하다면 구체적인 값을 요청하고, 해당 adapter가 지원하지 않으면 validation error로 처리한다.

`WorkspaceWrite.additionalWritableRoots`가 비어 있으면 추가 root가 없다는 뜻이다. 경로가 비어 있거나 중복되는지 등의 정규화는 모델 생성자가 수행하지 않는다.

### `NetworkAccess`

| 값 | 요청 의미 |
|---|---|
| `PROVIDER_DEFAULT` | provider의 현재 network 정책을 사용한다. |
| `DENIED` | outbound network를 허용하지 않는다. |
| `ALLOWED` | outbound network를 정책상 허용한다. |

`ALLOWED`는 연결, DNS, 자격 증명, 특정 서비스 권한이나 가용성을 보장하지 않는다. adapter가 특정 sandbox와 조합해서만 network 의도를 적용할 수 있으면(예: Codex는 workspace-write에서만) 다른 조합은 validate가 거절한다.

### `ApprovalPolicy`

| 값 | 요청 의미 |
|---|---|
| `PROVIDER_DEFAULT` | provider/runtime에 이미 설정된 approval 기본값을 사용한다. adapter SDK가 임의로 정한 convenience default가 아니다. |
| `DENY_ALL` | provider 정책상 승인이 필요한 효과를 거절한다. |
| `AGENT_REVIEWED` | provider의 자동 agent reviewer에게 판단을 위임한다. |
| `CALLER_DECIDES` | 승인이 필요한 효과마다 `InteractionRequested`를 발생시키고 caller의 `respond`를 기다린다. |

이 정책은 모든 도구 실행을 금지하거나 허용한다는 뜻이 아니다. provider 정책상 approval이 필요한 작업을 **누가** 결정하는지 표현한다. `CALLER_DECIDES`가 아닌 정책에서 provider가 그래도 승인을 요청하면 adapter는 거절하고 `Warning(CONFIGURATION)`을 낸다. 어떤 정책에서도 adapter가 스스로 승인하지 않는다.

## Interaction

`CALLER_DECIDES`에서 provider가 승인을 요청하면 execution은 `WAITING`이 되고 다음 타입이 오간다.

```kotlin
sealed interface InteractionRequest {
    val interactionId: InteractionId
    val workId: WorkId?
    val detail: JsonElement

    data class Approval(
        override val interactionId: InteractionId,
        override val workId: WorkId?,
        val prompt: String,                          // 명령줄, 변경 사유 등
        val effect: EffectKind?,                     // COMMAND, FILE_CHANGE, …
        val availableDecisions: Set<ApprovalDecision>,
        override val detail: JsonElement = JsonNull, // provider 원본
    ) : InteractionRequest
}

enum class ApprovalDecision { APPROVE_ONCE, APPROVE_FOR_SESSION, DECLINE, CANCEL }

sealed interface InteractionResponse {
    data class Approval(val decision: ApprovalDecision) : InteractionResponse
}

sealed interface InteractionResolution {
    data class Responded(val response: InteractionResponse) : InteractionResolution
    data class Cleared(val reason: ClearReason) : InteractionResolution
}

enum class ClearReason { TURN_COMPLETED, TURN_INTERRUPTED, SUPERSEDED, PROVIDER }
```

- `availableDecisions`는 이 request가 실제로 받는 결정만 담는다. 그 밖의 결정으로 `respond`하면 `IllegalArgumentException`이다.
- `DECLINE`은 이 작업만 거절하고 loop는 계속한다. `CANCEL`은 거절하면서 provider에 loop 중단을 요청한다.
- request는 caller 응답 없이도 닫힐 수 있다(`Cleared`): turn이 끝나거나 중단되었을 때, provider가 철회했을 때.
- 사용자 질문(Questions) 종류는 0.1.0에 없다. 현재 pinned SDK 어느 쪽도 그 request를 노출하지 않는다.

권장 소비 패턴은 [lifecycle-and-concurrency.md](lifecycle-and-concurrency.md)의 "Interaction" 절에 있다.

## 실행 입력

### `AgentInput.Text`

현재 공통 포트가 지원하는 입력은 non-empty 사용자 text다. 빈 문자열은 거절하지만 whitespace-only 문자열은 유효하다. adapter는 사용자 text를 임의로 trim하거나 지시를 덧붙이지 않는다. provider가 요구하는 skill activation envelope는 예외이며, 그 경우에도 사용자 text 자체는 보존된다.

## 식별자

| 타입 | 범위 | 소비자 규칙 |
|---|---|---|
| `ProviderId` | adapter 종류 | 설정과 provider 선택에 사용하며 display name으로 간주하지 않는다. |
| `SessionId` | provider의 durable conversation | provider와 함께 저장하고 파싱하거나 직접 만들지 않는다. |
| `ExecutionId` | 최소한 하나의 harness runtime | event 상관관계에 사용하고 opaque 값으로 취급한다. |
| `WorkId` | 하나의 execution | tool/effect lifecycle을 연결하며 execution 밖에서 유일하다고 가정하지 않는다. |
| `InteractionId` | 하나의 execution | `respond`에 사용하며 execution 밖에서 유일하다고 가정하지 않는다. |

모든 식별자는 blank 값을 거절하는 Kotlin value class다.

## Compatibility 모델

```kotlin
val report = harness.validate(spec)

report.issues.forEach { issue ->
    println("${issue.severity}: ${issue.path}: ${issue.message}")
}

report.requireCompatible()
```

### `CompatibilityIssue`

- `path`: `AgentSpec` 안의 의미 위치를 나타내는 dot-separated 경로다. 예: `executionPolicy.network`.
- `message`: 왜 의미를 보존할 수 없는지 설명한다.
- `severity`: `WARNING` 또는 `ERROR`다.

### `CompatibilityReport`

- `isCompatible`: `ERROR`가 없을 때만 `true`다.
- `requireCompatible()`: error가 있으면 모든 issue를 가진 `IncompatibleAgentSpecException`을 던진다.
- `Compatible`: issue가 없는 공유 report다.

## 결과와 사용량

```kotlin
data class AgentResult(
    val finalMessage: String,
    val stopReason: StopReason = StopReason.FINISHED,
    val usage: AgentUsage? = null,          // 이 execution의 누적
    val sessionUsage: AgentUsage? = null,   // provider가 보고할 때만, session 전체 누적
)

enum class StopReason { FINISHED, TURN_LIMIT, LOOP_DETECTED, PROVIDER_STOPPED }
```

- `finalMessage`는 성공한 execution의 canonical user-facing 최종 응답이며 빈 문자열일 수 있다.
- `stopReason`은 왜 멈췄는지다. `FINISHED`만 agent가 스스로 끝냈다는 뜻이다. 한도 도달·loop 감지·provider 중단은 **실패가 아니라 `COMPLETED` + `stopReason != FINISHED`**다. provider가 대화를 정상 상태로 남겨 두므로 다음 `execute`로 이어갈 수 있고, 이어갈지는 workflow가 결정한다.
- `usage`는 이 execution 안에서의 누적이다. 이전 execution의 사용량은 섞이지 않는다.
- `sessionUsage`는 provider가 session 단위 누적을 보고할 때만 있다.

`AgentUsage`의 각 필드는 nullable이다.

- `null`: provider가 신뢰할 값을 제공하지 않음
- `0`: 실제로 보고된 0
- `totalTokens`: provider가 보고한 total이며 다른 필드의 단순 합이라고 가정하지 않음

`UsageChanged`는 증분치가 아니라 그 시점까지 알려진 누적 snapshot이며 `execution`과 `session` 두 범위를 따로 담는다.

## 예외

| 예외 | 발생 시점 | 의미 |
|---|---|---|
| `IllegalArgumentException` | 값 객체 생성, `respond` | blank ID/skill, empty input처럼 모델 불변조건이 깨졌거나, request와 맞지 않는 응답이다. |
| `IllegalStateException` | `execute`, `respond` | 이전 execution이 terminal이 아니거나 session이 release되었거나, interaction이 닫혔다. |
| `IncompatibleAgentSpecException` | session create/resume | adapter가 요청한 의미를 보존할 수 없다. |
| `HarnessTransportException` | runtime 시작, request, protocol 경계 | provider SDK/runtime과 통신하거나 유효한 응답을 받지 못했다. host가 죽은 harness는 이후 모든 요청에 이 예외를 던진다. |
| `AgentExecutionFailedException` | `awaitResult()` | execution이 실패했다. `kind: FailureKind`로 분류된다. |
| `AgentExecutionCancelledException` | `awaitResult()` | execution이 취소되었다. |

```kotlin
enum class FailureKind {
    TRANSIENT,          // rate limit, overload, connection loss, provider 5xx — 재시도 가능성 있음
    AUTHENTICATION,     // 인증·권한 실패
    POLICY_BLOCKED,     // provider 정책·sandbox·안전 필터
    CONTEXT_OVERFLOW,   // context window 초과
    BUDGET_EXCEEDED,    // session/account 예산 소진
    PROVIDER,           // provider가 보고한 그 밖의 실패
    TRANSPORT,          // SDK host process·transport 실패
    UNKNOWN,
}
```

`AgentExecutionException`은 두 예외의 sealed 상위 타입이다. provider 요청이 실패하는 시점에 따라 `AgentSession.execute()`에서 `HarnessTransportException`이 발생하여 handle이 만들어지지 않을 수도 있고, handle 생성 후에는 terminal state와 위 예외로 보고될 수도 있다.

## 기본 포트가 의도적으로 다루지 않는 것

다음 기능은 공통 기본 포트가 아니라 선택 capability 후보다. 근거와 재검토 조건은 [capability-candidates.md](capability-candidates.md)에 있다.

- provider 전용 conversation list/archive/fork/name
- host-defined custom tool 등록
- active execution steering
- structured output 및 provider 고유 multimodal input
- MCP/plugin/connector 설치와 관리
- caller-selected context token ceiling
- 사용자 질문 interaction

이 기능이 없다는 것은 agent가 내부적으로 도구를 사용하지 못한다는 뜻이 아니다. 기본 포트는 그 작업을 `ToolCallChanged`와 `EffectChanged`로 관찰한다. 자세한 기준은 [semantic-contract.md](semantic-contract.md)를 참고한다.

## 다음에 읽을 문서

- 이벤트를 정확히 소비하려면 [event-contract.md](event-contract.md)
- session/execution을 안전하게 관리하려면 [lifecycle-and-concurrency.md](lifecycle-and-concurrency.md)
- 포트에 포함하는 기준을 이해하려면 [semantic-contract.md](semantic-contract.md)
- provider별 실제 대응을 보려면 [provider-mapping.md](provider-mapping.md)
- Maven과 runtime 배포를 보려면 [distribution.md](distribution.md)
- adapter를 테스트하려면 [testing.md](testing.md)
