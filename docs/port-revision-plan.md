# 포트 개정 계획 v2 (0.1.0 이전)

> 상태: **구현 완료** (2026-09-03). 아래 "구현 결과"를 먼저 읽는다. 본문은 착수 시점의 계획이다.  
> 작성일: 2026-09-03  
> 근거: 2026-09-03 설계 리뷰와 저자 재검토 (Wrapper Agent App 개발자 관점)  
> 적용 버전: `0.1.0-SNAPSHOT` → `0.1.0`. 아직 첫 커밋 전이므로 호환성 shim 없이 포트를 바꾼다.

## 구현 결과

| 범위 | 결과 |
|---|---|
| Part C | `semantic-contract.md` 다섯 기준으로 재서술, `ContextPolicy`·metadata·`ExecutionOptions` 제거, `capability-candidates.md` 신설 |
| D1–D5 | `harness-adapter-testkit`(RecordingBridge, `AgentHarnessContractTest`, `SpecSpace`/`IntentProjection`), `ProcessLifecycleTest`(실제 host process), `bridges/tests`(pytest + stub App Server, node --test), `hostTests`/`-PstrictHostTests` Gradle task, `testing.md` |
| B8 | mailbox/actor 분리, collector별 bounded queue + `ObservationGap`, terminal exactly-once·last, host death → `FAILED(TRANSPORT)`(bridge는 재시작하지 않음), close → `CANCELLED`, `SdkBridge.release` |
| B5, A5 | session gate(`IllegalStateException`), `AgentSession.release()`, host `release_session` |
| 8a | 성공. [spikes/2026-09-03-codex-low-level-client.md](spikes/2026-09-03-codex-low-level-client.md). host를 `openai_codex.client.CodexClient`로 이관 |
| B1 | 선호안: `PROVIDER_DEFAULT`는 approval 필드 생략. handler 정책 표 구현(어떤 정책에서도 자동 accept 없음) |
| B2, B3 | Gemini null instructions 생략, Codex network 의도는 workspace-write 외 조합을 validate ERROR |
| B4 | Codex `tokenUsage.last/total` 중첩 파싱, `execution = total − baseline`, Gemini field별 합산, `UsageChanged(execution, session)`, `AgentResult.sessionUsage` |
| B6 | resume = desired configuration, 응답 ID 사용 |
| A3, A4 | `FailureKind`(+`BUDGET_EXCEEDED`), sealed `AgentExecutionException` → Failed/Cancelled, `StopReason`, `WarningKind`, Codex `willRetry` → `Warning(RECOVERABLE)`, `WorkStatus.CANCELLED`, finalMessage FINAL-only |
| 8b | stub App Server로 round-trip과 unblock-before-interrupt 검증. **실제 App Server fixture는 로그인 환경 필요 — 미확보** |
| A1, A2 | `CALLER_DECIDES`, `WAITING`, `InteractionRequest.Approval`, `pendingInteractions`, `respond()`, `InteractionResolved(Responded/Cleared)`. Questions 종류는 제외 |
| 문서 | protocol-reference, event-contract, lifecycle-and-concurrency, provider-mapping, README, distribution, bridge-protocol(신규) 동기화 |

남은 것: 실제 Codex App Server의 `requestApproval` payload fixture 캡처(로그인 필요), Gemini SDK build에서 `instructions` 필수 여부 확인.

## 관점

이 저장소는 Codex와 Gemini CLI를 **포트 밖의 agent harness 공급자**로 본다. 포트는 두 SDK의 공통 모양이 아니라, 그 위에서 agent workflow를 만드는 소비자(Wrapper Agent App)가 필요로 하는 **목적**을 선언한다.

그 소비자가 workflow의 한 step에서 harness에게 요구하는 것은 다섯 가지다.

1. 입력으로 한 번의 agent loop를 시작한다.
2. 진행과 외부 효과를 관찰한다.
3. 다음 step으로 분기할 수 있을 만큼 풍부한 결과를 받는다.
4. 실행 중에 개입한다 — 승인, 질문 응답, 중단.
5. 나중에 같은 대화를 다시 연다.

현재 포트는 1·2·5를 만족하고, 3은 부분적이며, 4는 `cancel`만 있다. 이 문서는 그 간극을 메우는 계획(Part A), 문서가 세운 계약을 어댑터가 어기는 지점의 수정(Part B), 포트 포함 기준의 재서술(Part C), 그것을 지키게 하는 테스트(Part D)를 as-is / to-be로 정리한다. 이 네 범위 밖의 이슈는 문서 끝 [Post-POC](#post-poc-이슈)에 모아 둔다.

### v2에서 확정한 원칙

1. **의미를 먼저 정하고 SDK 수단은 나중에 고른다.** 다만 적어도 한 어댑터가 공개·지원 API로 그 의미를 실제 보존한다는 spike가 없으면 public port 시그니처를 확정하지 않는다.
2. **lifecycle은 관찰 stream과 독립적이어야 한다.** 느리거나 없는 event collector, host crash, harness close 때문에 `awaitResult()`가 매달려서는 안 된다.
3. **조용한 의미 축소는 금지한다.** 의미를 보존할 수 없으면 `validate`의 `ERROR` 또는 요청 시점의 명시적 예외로 거절한다. `WARNING`은 요청한 의미가 그대로 지켜지면서 부가 정보만 손실될 때만 쓴다.
4. **resume의 `AgentSpec`은 과거 설정의 hash가 아니라 앞으로 적용할 desired configuration이다.** override할 수 없는 충돌만 거절한다.
5. **공식 문서와 설치된 SDK를 구분한다.** Codex App Server가 지원하더라도 현재 `openai-codex` 공개 API가 노출하지 않으면 즉시 구현 가능하다고 간주하지 않는다.
6. **red test는 로컬 구현 순서이지 저장소 상태가 아니다.** 각 완결된 commit과 `main`은 항상 green이어야 한다.

검증 기준은 Codex [공식 App Server 문서](https://developers.openai.com/codex/app-server), 저장소에 설치된 `openai-codex==0.147.0`의 public API, Gemini CLI 공식 SDK 설계와 실제 build artifact다. App Server 문서가 확인해 주는 것은 wire-level 가능성이고, Kotlin adapter 구현 가능성은 pinned SDK spike와 contract test로 별도 증명한다.

## 목차

- [Part A. 포트 변경](#part-a-포트-변경)
  - [A1. 실행 중 caller 개입](#a1-실행-중-caller-개입)
  - [A2. `ApprovalPolicy.CALLER_DECIDES`](#a2-approvalpolicycaller_decides)
  - [A3. `FailureKind`](#a3-failurekind)
  - [A4. `StopReason`과 `WarningKind`](#a4-stopreason과-warningkind)
  - [A5. Session runtime 해제](#a5-session-runtime-해제)
- [Part B. 계약 위반 수정](#part-b-계약-위반-수정)
- [Part C. semantic-contract 기준 재서술](#part-c-semantic-contract-기준-재서술)
- [Part D. contract test 확장](#part-d-contract-test-확장)
- [Part E. 실행 순서](#part-e-실행-순서)
- [Post-POC 이슈](#post-poc-이슈)

---

## Part A. 포트 변경

### A1. 실행 중 caller 개입

#### as-is

```kotlin
interface AgentExecution {
    val id: ExecutionId
    val sessionId: SessionId
    val state: StateFlow<ExecutionState>   // STARTING, RUNNING, COMPLETED, FAILED, CANCELLED
    val events: Flow<AgentEvent>
    suspend fun cancel()
    suspend fun awaitResult(): AgentResult
}
```

- Execution은 "시작 → 관찰 → 중단/결과"의 단방향 handle이다.
- provider가 caller의 결정을 기다리는 상황(승인 요청, 사용자 질문)을 표현하는 상태·이벤트·응답 경로가 없다.
- `semantic-contract.md`와 `protocol-reference.md`는 interactive approval callback을 "선택 capability 후보"로 분류한다.
- `WorkStatus.DECLINED`는 있으나, 누가·언제 거절했는지 caller가 관여할 수 없다.

문제: 이 기능은 얹는 확장이 아니라 **Execution 상태 기계의 일부**다. 나중에 `ExecutionState`에 값을 추가하면 소비자의 exhaustive `when`이 전부 깨진다. 0.1.0 전에 자리를 잡아야 한다.

#### to-be

interaction이 execution control-plane의 일부라는 결정은 유지한다. 다만 v1처럼 타입을 먼저 확정하지 않고, 아래 Codex SDK spike가 성공한 뒤 공개 타입을 확정한다. spike가 실패하면 `CALLER_DECIDES`와 interaction API는 0.1.0에 넣지 않고 capability 후보에 남긴다.

**선행 spike — 공개 SDK만 사용**

현재 `openai-codex` 0.147.0의 고수준 `ApprovalMode`는 `deny_all`, `auto_review`만 제공한다. 저수준 `CodexClient`에는 동기 `approval_handler`가 있으나 현재 bridge가 쓰는 `AsyncCodex` 생성자에는 handler가 노출되지 않는다. 따라서 다음을 실제 runtime으로 증명한다.

확인된 구조적 사실 (0.147.0 코드 기준):

- `Codex`(`api.py:82`)와 `AsyncCodex`(`api.py:297`)는 모두 `CodexClient(config=config)`를 handler 없이 생성한다. 고수준 API에는 handler 주입 경로가 없다. 따라서 "공개 API만"이라는 조건은 raw `CodexClient` JSON-RPC 위에 thread start/resume/turn/stream을 bridge가 직접 구성한다는 뜻이다. 이는 spike가 아니라 **client 이관 작업**이며 B1 선호안도 같은 이관에 의존한다.
- `CodexClient._reader_loop`(`client.py:803-811`)는 `_handle_server_request`를 **reader thread에서 동기 호출**하고 반환값을 응답으로 쓴다. handler가 블록하면 `turn/interrupt`의 응답을 포함해 어떤 메시지도 읽히지 않는다. deadlock은 확인 대상이 아니라 **확정된 제약**이다.
- generated 타입에 `requestUserInput` 계열이 없다. 사용자 질문 request는 pinned SDK로 도달할 수 없다.

spike는 두 단계로 나눈다.

**8a. 저수준 client 이관 viability** (B1보다 먼저)

1. 공개 API만 사용한다. private field mutation이나 generated internal type 직접 의존은 허용하지 않는다.
2. raw `CodexClient`로 `thread/start`(approval 필드 생략), `thread/resume`, `turn/start`, turn notification 구독, `turn/interrupt`, close를 stub app-server 대상으로 구동할 수 있는지 확인한다.
3. 성공하면 B1은 선호안(필드 생략)으로, 실패하면 fallback(ERROR)으로 확정하고 A1/8b는 자동 탈락한다.

**8b. approval round-trip** (8a 성공 시)

1. handler는 `queue.get()`으로 블록하되, **cancel/close 경로는 먼저 queue에 decline을 넣어 handler를 풀고 나서 interrupt/close를 보낸다.** 이 순서 제약을 host 코드와 `docs/bridge-protocol.md`에 명시한다.
2. `WAITING` 동안 notification(usage 등)이 흐르지 않는 것은 허용된 동작으로 문서화한다.
3. command/file approval request의 fixture와 `availableDecisions`를 저장한다.
4. 성공 기준은 request 수신 → Kotlin event → caller response → provider 재개와, 미응답 상태에서 cancel/close가 handler를 먼저 풀고 종료되는 것이다.

Codex App Server가 approval과 user-input request를 지원한다는 사실만으로 이 spike를 통과한 것으로 보지 않는다. 공식 wire protocol 지원과 현재 SDK의 공개 API 지원은 별개다.

**상태**

```kotlin
enum class ExecutionState {
    STARTING,
    RUNNING,
    /** 하나 이상의 interaction이 caller 응답 또는 provider 정리를 기다린다. */
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
```

```text
                 ┌────────────────────────────┐
                 ▼                            │ 마지막 request가 닫힘
STARTING ──> RUNNING ── request 발생 ──> WAITING
    │           │                         │ ├──> COMPLETED
    │           │                         │ ├──> FAILED
    │           ├──> COMPLETED             │ └──> CANCELLED
    │           ├──> FAILED                │
    │           └──> CANCELLED             └── cancel/close
    ├──> FAILED
    └──> CANCELLED
```

- `WAITING`은 non-terminal이다. 마지막 열린 request가 닫히고 execution이 아직 active일 때만 `RUNNING`으로 돌아간다.
- 여러 request가 동시에 열릴 수 있다. `pendingInteractions`가 authoritative snapshot이다.
- provider가 timeout, turn 완료·중단, supersede로 request를 caller 응답 없이 정리할 수 있다.

**식별자와 request 모델**

```kotlin
@JvmInline
value class InteractionId(val value: String) {
    init { require(value.isNotBlank()) }
}

sealed interface InteractionRequest {
    val interactionId: InteractionId
    val workId: WorkId?
    val detail: JsonElement

    data class Approval(
        override val interactionId: InteractionId,
        override val workId: WorkId?,
        val prompt: String,
        val effect: EffectKind?,
        /** 이 request가 실제로 허용하는 결정만 포함한다. */
        val availableDecisions: Set<ApprovalDecision>,
        override val detail: JsonElement = JsonNull,
    ) : InteractionRequest

    // Questions(사용자 질문)는 0.1.0에 넣지 않는다. pinned SDK에 대응 request가 없어
    // 기준 3(최소 한 어댑터의 구현 증명)을 만족하지 못한다. sealed 확장 자리만 남긴다.
}

enum class ApprovalDecision {
    APPROVE_ONCE,
    /** 현재 AgentSession에 남은 실행에도 적용. provider가 제시할 때만 선택 가능하다. */
    APPROVE_FOR_SESSION,
    /** 이 작업만 거절하고 agent loop는 계속한다. */
    DECLINE,
    /** 이 작업을 거절하고 provider가 turn을 중단한다 (Codex `cancel`). 의미는 8b fixture로 확정한다. */
    CANCEL,
}

sealed interface InteractionResponse {
    data class Approval(val decision: ApprovalDecision) : InteractionResponse
}

sealed interface InteractionResolution {
    data class Responded(val response: InteractionResponse) : InteractionResolution
    data class Cleared(val reason: ClearReason) : InteractionResolution
}

enum class ClearReason {
    TURN_COMPLETED,
    TURN_INTERRUPTED,
    SUPERSEDED,
    PROVIDER,
}
```

`ApproveForExecution`은 두지 않는다. Codex가 제공하는 portable scope는 한 번 또는 session이며, 지원하지 않는 scope를 `WARNING`과 함께 축소 적용하지 않는다. caller는 각 request의 `availableDecisions`에 포함된 결정만 보낼 수 있다.

**이벤트와 Execution 포트**

```kotlin
sealed interface AgentEvent {
    // ...기존 이벤트...

    data class InteractionRequested(
        override val executionId: ExecutionId,
        val request: InteractionRequest,
    ) : AgentEvent

    data class InteractionResolved(
        override val executionId: ExecutionId,
        val interactionId: InteractionId,
        val resolution: InteractionResolution,
    ) : AgentEvent
}

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

`respond`는 unknown/closed ID, request 종류와 response 불일치, request가 광고하지 않은 approval decision을 호출자 오류로 거절한다. `respond` 반환은 provider가 응답을 받았다는 뜻이지 반드시 `RUNNING`이 되었다는 뜻은 아니다.

**bridge 프로토콜 확장**

| 방향 | 메시지 | 내용 |
|---|---|---|
| Kotlin → host | request `respond_interaction` | `{executionId, interactionId, response}`. request별 지원 decision을 그대로 보존한다. |
| host → Kotlin | event `interaction_requested` | provider request를 host가 pending map에 등록한 뒤 보내는 정규화 envelope. 원본 payload 포함. |
| host → Kotlin | event `interaction_resolved` | `{interactionId, resolution}`. caller 응답뿐 아니라 provider cleanup도 표현한다. |

host는 request에 응답을 전달하기 전에 pending entry를 지우지 않는다. 중복 응답과 이미 정리된 request는 명시적으로 실패시킨다. terminal 확정 시 남은 interaction은 모두 `Cleared`로 닫고 snapshot을 비운다.

**provider 대응**

| | Codex | Gemini CLI |
|---|---|---|
| APPROVAL | 선행 spike가 성공할 때만 호환. App Server의 command/file/network/permission approval을 request별 `availableDecisions`와 함께 매핑한다. | 공식 SDK의 approval wiring이 없으면 `CALLER_DECIDES`를 `ERROR`로 거절한다. |
| QUESTION | 0.147.0 generated 타입에 없음. 0.1.0 범위 밖 (capability-candidates). | SDK 미노출. 0.1.0 범위 밖. |

**문서와 구현 변경**

- `protocol-reference.md`: `pendingInteractions`, `respond`, request/response/resolution 타입과 decision validation.
- `event-contract.md`: request → waiting → responded/cleared 순서와 provider cleanup.
- `lifecycle-and-concurrency.md`: 다중 pending, timeout, cancel/close, snapshot 소비 패턴.
- `semantic-contract.md`: caller intervention을 core execution control-plane으로 설명하되 SDK spike gate를 기록.
- `provider-mapping.md`: 실제 SDK 0.147.0 handler/API와 provider별 decision 표.
- `BridgeAgentExecution`: interaction snapshot 갱신은 public event emit과 분리된 lifecycle actor에서 수행한다(B8).

---

### A2. `ApprovalPolicy.CALLER_DECIDES`

#### as-is

```kotlin
enum class ApprovalPolicy { PROVIDER_DEFAULT, DENY_ALL, AGENT_REVIEWED }
```

- 값 집합이 Codex `ApprovalMode { deny_all, auto_review }`의 이름 변경이다.
- 목적("승인이 필요한 효과를 누가 결정하는가")의 세 답 중 "caller"가 빠져 있다.

#### to-be

```kotlin
enum class ApprovalPolicy {
    PROVIDER_DEFAULT,
    /** provider 정책상 승인이 필요한 효과를 모두 거절한다. */
    DENY_ALL,
    /** provider의 자동 reviewer에게 위임한다. */
    AGENT_REVIEWED,
    /** 승인이 필요한 효과마다 [AgentEvent.InteractionRequested]를 발생시키고 caller의 [AgentExecution.respond]를 기다린다. */
    CALLER_DECIDES,
}
```

**의미**

- `CALLER_DECIDES`는 "무엇이 승인 대상인가"를 바꾸지 않는다. 그것은 여전히 provider 정책이다. "누가 답하는가"만 caller로 바꾼다.
- `FilesystemAccess.FullAccess` + `CALLER_DECIDES`처럼 provider가 승인 자체를 요구하지 않는 조합은 유효하다. 승인 요청이 0번 발생할 뿐이다.

**validate**

| adapter | 판정 |
|---|---|
| Codex | A1의 공개 SDK spike가 성공한 뒤에만 호환. 실패하면 0.1.0에서도 ERROR로 유지한다. App Server wire protocol의 지원만으로 호환 판정을 내리지 않는다. |
| Gemini CLI | ERROR `executionPolicy.approval` — 기존 `SDK_POLICY_LIMITATION` 메시지 재사용 |

`CALLER_DECIDES`를 enum에 먼저 넣고 모든 adapter가 거절하는 중간 상태를 release하지 않는다. spike와 Codex 구현, contract test, 문서 변경을 한 작업 단위로 완료한다.

**문서 변경**: `protocol-reference.md` ApprovalPolicy 표, `provider-mapping.md` 정책 표, `semantic-contract.md`의 "capability와의 경계"에서 interactive approval 제거.

---

### A3. `FailureKind`

#### as-is

```kotlin
data class ExecutionFailed(override val executionId: ExecutionId, val message: String) : AgentEvent
class AgentExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- 실패 원인이 free text다. workflow가 재시도/포기/에스컬레이션을 결정할 수 없다.
- 취소와 실패 모두 `AgentExecutionException`이고, 구분하려면 `state.value`를 다시 읽어야 한다(`lifecycle-and-concurrency.md`의 권장 패턴이 실제로 그렇게 안내한다).
- `BridgeAgentExecution`의 stream 예외, provider `error`, `turn/completed(status=failed)`가 모두 같은 모양으로 합쳐진다.

#### to-be

```kotlin
enum class FailureKind {
    /** 재시도하면 성공할 가능성이 있는 일시 오류 (rate limit, 네트워크, provider 5xx) */
    TRANSIENT,
    /** 인증·권한 문제. 재시도 무의미 */
    AUTHENTICATION,
    /** provider 정책·sandbox·안전 필터가 실행을 막음 */
    POLICY_BLOCKED,
    /** context window 초과로 진행 불가 */
    CONTEXT_OVERFLOW,
    /** 요청이 provider에 도달했으나 provider가 실패로 보고 (모델 오류 등) */
    PROVIDER,
    /** SDK host process·transport 경계의 실패 */
    TRANSPORT,
    UNKNOWN,
}

data class ExecutionFailed(
    override val executionId: ExecutionId,
    val kind: FailureKind,
    val message: String,
) : AgentEvent
```

```kotlin
sealed class AgentExecutionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class AgentExecutionFailedException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : AgentExecutionException(message, cause)

class AgentExecutionCancelledException(
    message: String = "Agent execution was cancelled",
) : AgentExecutionException(message)
```

소비 패턴이 다음처럼 바뀐다.

```kotlin
try {
    execution.awaitResult()
} catch (cancelled: AgentExecutionCancelledException) {
    // 취소
} catch (failed: AgentExecutionFailedException) {
    when (failed.kind) {
        FailureKind.TRANSIENT -> retryLater()
        FailureKind.CONTEXT_OVERFLOW -> startFreshSession()
        else -> escalate(failed)
    }
}
```

**매핑**

| 출처 | kind |
|---|---|
| `BridgeAgentExecution` source flow 예외, `HarnessTransportException` | TRANSPORT |
| Codex `error.codexErrorInfo` — `UsageLimitExceeded`, retry 가능한 connection/5xx 계열 | TRANSIENT. 즉시 재시도 가능하다는 보장은 없으며 backoff/리셋 시각은 애플리케이션 정책이다. |
| Codex `error.codexErrorInfo` — `Unauthorized` 또는 HTTP 401 | AUTHENTICATION |
| Codex `error.codexErrorInfo` — `SandboxError`, 명시적 policy block | POLICY_BLOCKED |
| Codex `error.codexErrorInfo` — `ContextWindowExceeded` | CONTEXT_OVERFLOW |
| Codex `turn/completed(status=failed)` | PROVIDER (error payload에 분류 가능한 code가 있으면 세분화) |
| Gemini `agent_execution_blocked` | POLICY_BLOCKED |
| Gemini `invalid_stream` | PROVIDER |
| Gemini `error` — `context_window_will_overflow` 직후 또는 overflow 메시지 | CONTEXT_OVERFLOW |
| Gemini `error` — 그 외 | PROVIDER |
| 매핑 불가 | UNKNOWN |

Codex는 공식 App Server의 `codexErrorInfo`와 `httpStatusCode`를 우선 사용한다. 구조화된 code가 없을 때만 보수적인 문자열 휴리스틱을 사용하며, 불확실하면 `PROVIDER` 또는 `UNKNOWN`으로 둔다. 인증·재시도처럼 workflow 동작을 바꾸는 분류를 free text만으로 단정하지 않는다. 모든 매핑은 `provider-mapping.md`에 표로 남기고 원본 payload를 `ProviderEventObserved`로 확인 가능하게 둔다.

**문서 변경**: `protocol-reference.md` 예외 표, `event-contract.md` `ExecutionFailed` 절, `lifecycle-and-concurrency.md` 취소 패턴(`state.value` 재확인 패턴 제거).

---

### A4. `StopReason`과 `WarningKind`

#### as-is

```kotlin
data class AgentResult(val finalMessage: String, val usage: AgentUsage? = null)
data class Warning(override val executionId: ExecutionId, val message: String) : AgentEvent
```

- Gemini `max_session_turns`, `loop_detected`, `agent_execution_stopped`는 free-text `Warning` 후 `execution_completed`로 종결된다. **한도로 멈춘 실행이 성공으로 보고된다.**
- `context_window_will_overflow`도 같은 통로로 사라진다.
- 소비자는 "agent가 스스로 끝냈는가, 멈춰졌는가"를 구분할 수 없다.

#### to-be

```kotlin
enum class StopReason {
    /** agent가 스스로 작업을 끝냈다. */
    FINISHED,
    /** provider의 turn/step 한도에 도달해 멈췄다. finalMessage는 그 시점까지의 내용. */
    TURN_LIMIT,
    /** provider가 반복 루프를 감지해 멈췄다. */
    LOOP_DETECTED,
    /** provider가 그 외 이유로 loop를 중단했으나 실패로 보고하지 않았다. */
    PROVIDER_STOPPED,
}

data class AgentResult(
    val finalMessage: String,
    val stopReason: StopReason,
    val usage: AgentUsage? = null,
)
```

**결정: 한도 도달은 `COMPLETED` + `stopReason != FINISHED`다.** 실패로 분류하지 않는 이유는 provider가 대화를 정상 상태로 남겨 두고, 다음 `execute`로 이어갈 수 있기 때문이다. 이어갈지 여부는 workflow가 `stopReason`으로 결정한다. 이 규칙을 `event-contract.md`의 "Terminal 이벤트와 결과"에 명시한다.

```kotlin
enum class WarningKind {
    /** context가 한도에 가까워 provider가 곧 compaction하거나 실패할 수 있다. */
    CONTEXT_PRESSURE,
    /** provider 설정 경고 */
    CONFIGURATION,
    OTHER,
}

data class Warning(
    override val executionId: ExecutionId,
    val kind: WarningKind,
    val message: String,
) : AgentEvent
```

**매핑**

| provider 이벤트 | to-be |
|---|---|
| Gemini `max_session_turns` | `stopReason = TURN_LIMIT` (매퍼가 기억했다가 `execution_completed`에서 사용). Warning은 내지 않음 |
| Gemini `loop_detected` | `stopReason = LOOP_DETECTED` |
| Gemini `agent_execution_stopped` | `stopReason = PROVIDER_STOPPED` |
| Gemini `context_window_will_overflow` | `Warning(CONTEXT_PRESSURE)` |
| Codex `configWarning` | `Warning(CONFIGURATION)` |
| Codex `warning` | `Warning(OTHER)` |
| Codex turn 정상 완료 | `FINISHED` (Codex가 step 한도 상태를 노출하면 구현 시 추가) |

**문서 변경**: `protocol-reference.md` "결과와 사용량", `event-contract.md` 경고·terminal 절, `provider-mapping.md`.

---

### A5. Session runtime 해제

#### as-is

`AgentSession`에는 독립적인 해제 API가 없고 두 host의 `sessions` map은 harness가 닫힐 때까지 항목을 유지한다. 서버형 wrapper가 하나의 harness로 많은 durable conversation을 다루면 사용이 끝난 session handle과 provider runtime resource가 계속 쌓인다.

#### to-be

동기 `AutoCloseable.close()` 안에서 suspend bridge request를 억지로 실행하지 않는다. 비동기 자원 경계를 그대로 드러낸다.

```kotlin
interface AgentSession {
    val id: SessionId
    val spec: AgentSpec

    suspend fun execute(input: AgentInput): AgentExecution

    /**
     * 이 harness process 안의 session handle과 runtime resource를 해제한다.
     * provider의 durable conversation은 삭제하지 않으며 같은 ID로 다시 resume할 수 있다.
     */
    suspend fun release()
}
```

규칙:

- `release()`는 idempotent다.
- active execution이 있으면 먼저 `cancel()`을 요청하고 terminal을 bounded wait한 뒤 host handle을 해제한다. timeout이어도 local handle은 닫고 execution을 명시적 cancellation 또는 transport failure로 완료한다.
- release 이후 `execute()`는 `IllegalStateException`이다.
- `release_session` host request는 `sessions`와 관련 spec/interaction entry를 제거한다. provider conversation 삭제나 archive를 호출하지 않는다.
- harness `close()`는 남은 모든 session에 같은 정리를 적용한다.

**테스트**: release의 idempotence, release 후 execute 거절, active execution release, host map 제거, 같은 durable ID resume.

**문서 변경**: `protocol-reference.md`, `lifecycle-and-concurrency.md`, `distribution.md`의 서버형 사용 예제.

---

## Part B. 계약 위반 수정

공통 원칙: **의미 판정은 Kotlin `validate`에서, host script는 판정 없는 번역기로.** 지금은 일부 판정(approval 기본값, network 적용 조건)이 Python 안에 숨어 있어 validate가 거짓 양성을 낸다.

### B1. `ApprovalPolicy.PROVIDER_DEFAULT` 의미 보존

**as-is** — `bridges/codex_sdk_bridge.py:thread_arguments`

```python
approval = spec.get("approval", "provider_default")
if approval != "provider_default" or creating:
    result["approval_mode"] = {
        "provider_default": ApprovalMode.auto_review,
        "deny_all": ApprovalMode.deny_all,
        "agent_reviewed": ApprovalMode.auto_review,
    }[approval]
```

`creating=True`면 항상 `approval_mode`를 넣고, `provider_default`를 `auto_review`로 바꾼다. `protocol-reference.md`의 "provider/runtime 기본값을 바꾸지 않는다"와 충돌한다.

더 중요한 점은 단순 인자 생략도 해결책이 아니라는 것이다. 설치된 `openai-codex` 0.147.0의 고수준 `thread_start()`는 `approval_mode=ApprovalMode.auto_review`를 기본값으로 갖는다. 즉 현재 `AsyncCodex` 경로에서는 생략해도 같은 의미가 적용된다.

**to-be**

먼저 포트 의미를 다음처럼 고정한다.

> `PROVIDER_DEFAULT`는 adapter가 approval policy를 선택하지 않고 provider/runtime에 이미 설정된 기본값을 사용한다. adapter SDK 자체가 임의로 정한 convenience default를 뜻하지 않는다.

구현 선택지는 둘뿐이다.

1. **선호**: A1 spike와 함께 공개 저수준 SDK API로 옮겨 `thread/start`의 approval 관련 필드를 실제로 생략한다. 이 경로를 택하면 `APPROVAL_MODES`에서 `provider_default`를 제거한다.
2. **fallback**: 지원 API로 생략할 수 없으면 `CodexHarness.validate`가 `executionPolicy.approval`에 `ERROR`를 내고 session 생성을 막는다.

요청 의미를 바꾸면서 `WARNING`만 내고 실행하는 선택지는 허용하지 않는다. `AGENT_REVIEWED`는 SDK의 `auto_review`, `DENY_ALL`은 `deny_all`에 명시적으로 매핑한다. `CALLER_DECIDES`는 A1 spike가 성공한 경우에만 추가한다.

**approval request가 도착했을 때 host handler의 동작 (정책과 무관하게 필수)**

`CodexClient._default_approval_handler`(`client.py:773`)는 command/file approval request에 **무조건 `accept`** 를 돌려준다. 저수준 client로 옮겨 approval 필드를 생략하면 사용자의 `config.toml`이 `on-request`이고 reviewer가 없을 때 App Server가 request를 보내고, handler를 꽂지 않으면 SDK가 전부 승인한다. 그래서 bridge는 어떤 정책에서도 **항상 자체 handler를 등록**하고 다음 표를 따른다.

| policy | request 도착 시 host handler |
|---|---|
| `DENY_ALL` | `decline` |
| `AGENT_REVIEWED` | 정상이면 도착하지 않는다. 도착하면 `decline` + `Warning(CONFIGURATION)` |
| `PROVIDER_DEFAULT` | `decline` + `Warning(CONFIGURATION)`. 소비자가 승인 흐름을 원하면 `CALLER_DECIDES`를 명시해야 한다. |
| `CALLER_DECIDES` | `InteractionRequested` → caller 응답 (A1) |

어느 경우에도 `accept`를 기본값으로 두지 않는다. 이 표는 A1 spike 결과와 무관하게 B1 구현에 포함한다.

**테스트**:

- Kotlin intent-projection: `PROVIDER_DEFAULT`가 호환이면 bridge envelope에는 의도가 보존되어야 한다.
- host test: 최종 SDK/App Server params에서 approval 필드가 실제로 부재함을 low-level client stub으로 검증한다.
- fallback 경로라면 `validate` ERROR와 SDK request 0건을 검증한다.
- `thread_arguments`에서 인자를 생략했다는 사실만 검사하는 테스트로 끝내지 않는다. SDK 기본 인자까지 포함한 경계를 검사한다.

### B2. `instructions = null`이 Gemini에서 `""`가 됨

**as-is** — `bridges/gemini_cli_sdk_bridge.mjs:agentOptions`

```js
const options = {
  instructions: spec.instructions ?? "",
  ...
};
```

`protocol-reference.md`: "`null`은 빈 문자열과 다르다 … adapter가 둘을 구분하지 못하면서 그 차이가 의미에 영향을 준다면 validation error를 반환해야 한다."

**to-be**

```js
const options = { skills: ... };
if (spec.instructions !== undefined && spec.instructions !== null) options.instructions = spec.instructions;
```

- `GeminiCliAgent` 생성자가 `instructions`를 optional로 받으면 위로 끝.
- required라면 `GeminiCliHarness.validate`가 `instructions == null`에 대해 ERROR `instructions: "Gemini CLI SDK requires explicit instructions; pass \"\" to request empty instructions"`를 낸다.
- Kotlin `toBridgeJson`은 이미 null을 생략하므로 변경 없음.

**테스트**: intent-projection(`instructions` 부재 ↔ JSON key 부재), `bridges/tests/gemini_bridge.test.mjs::agentOptions omits instructions when null`.

### B3. `NetworkAccess`가 WorkspaceWrite 외 sandbox에서 조용히 버려짐

**as-is** — `codex_sdk_bridge.py`

```python
sandbox_config = {}
if network != "provider_default":
    sandbox_config["network_access"] = network == "allowed"
...
if sandbox_config:
    config["sandbox_workspace_write"] = sandbox_config
```

network 의도가 `sandbox_workspace_write` 설정에만 실린다. `ReadOnly + ALLOWED`, `FullAccess + DENIED`, `ProviderDefault + DENIED`는 validate를 통과하지만 network 의도는 적용되지 않는다.

**to-be**

1. Codex config에서 read-only / full-access sandbox의 network를 제어할 수 있는지 구현 시 확인한다.
2. 제어 불가한 조합은 **`CodexHarness.validate`에서 ERROR**로 거절한다.

```kotlin
val filesystem = spec.executionPolicy.filesystem
val network = spec.executionPolicy.network
if (network != NetworkAccess.PROVIDER_DEFAULT && filesystem !is FilesystemAccess.WorkspaceWrite) {
    add(CompatibilityIssue(
        path = "executionPolicy.network",
        message = "Codex SDK applies a network policy only to the workspace-write sandbox; " +
            "request FilesystemAccess.WorkspaceWrite or NetworkAccess.PROVIDER_DEFAULT",
    ))
}
```

3. `FullAccess + DENIED`는 의미상 모순에 가깝지만(sandbox 없음 + 네트워크 차단) 포트는 그 조합을 금지하지 않는다. Codex가 표현 못 하면 위와 같이 거절하고, 표현 가능하면 번역한다.
4. Python은 판정 없이 받은 값을 그대로 config에 넣는다.

**테스트**: intent-projection이 "validate 통과 → JSON에 `network`가 있고 `filesystem == workspace_write`"를 확인. 거절 케이스는 `CodexHarnessContractTest::rejects network intent outside workspace-write sandbox`.

### B4. usage의 "execution별 누적 snapshot" 의미가 양쪽에서 깨짐

**as-is**

- 포트: `UsageChanged.usage`는 "이 execution의 누적 snapshot", `AgentResult.usage`는 그 최종값.
- Codex `CodexEventMapper.mapUsage`: `thread/tokenUsage/updated.tokenUsage`에는 `last`와 `total`이 중첩되어 있는데 현재 매퍼는 바깥 객체를 곧바로 `toUsage()`에 넣는다. 따라서 지금은 session usage가 섞이는 문제 이전에 token field가 모두 `null`로 매핑될 수 있다. 이 notification 자체는 active thread 범위다.
- Gemini `GeminiEventMapper.mapUsage`: `finished.usageMetadata`는 **모델 호출 1회** 사용량이다. tool loop가 있는 turn은 `finished`가 여러 번 오고, 마지막 값만 남는다(SDK 구현에서 `finished` 발생 횟수를 구현 시 확인).

**to-be**

포트 정의는 유지하되 두 범위를 모두 노출한다.

```kotlin
data class UsageChanged(
    override val executionId: ExecutionId,
    /** 이 execution 안에서의 누적 */
    val execution: AgentUsage,
    /** provider가 제공할 때만, session 전체 누적 */
    val session: AgentUsage? = null,
) : AgentEvent

data class AgentResult(
    val finalMessage: String,
    val stopReason: StopReason,
    val usage: AgentUsage? = null,          // execution 누적
    val sessionUsage: AgentUsage? = null,   // provider가 제공할 때만
)
```

어댑터 책임:

| | execution 누적 계산 | session 누적 |
|---|---|---|
| Codex | 먼저 실제 두 turn과 tool loop fixture로 `last`가 "마지막 모델 호출"인지 "현재 turn 누적"인지 확인한다. 모델 호출 단위면 execution 안에서 합산하고, turn 누적이면 최신 snapshot으로 교체한다. 확인 전에는 추측으로 더하지 않는다. | `tokenUsage.total` 그대로 |
| Gemini | 매 `finished.usageMetadata`를 field별 null-aware 합산 (`null + n = n`, `null + null = null`). `totalTokens`도 합산 | 제공하지 않음 → `null` |

`AgentUsage`에 합산 helper를 둔다.

```kotlin
operator fun AgentUsage.plus(other: AgentUsage): AgentUsage
```

**문서 변경**: `event-contract.md` "Context와 usage", `protocol-reference.md` "결과와 사용량", `provider-mapping.md` usage 행.

**테스트**: `GeminiEventMapperTest::accumulates usage across multiple finished events`, `CodexEventMapperTest::reads nested last and total`, `CodexEventMapperTest::execution usage excludes previous executions of the thread`, 그리고 같은 session에서 두 execution을 순차 실행하는 fixture. Codex fixture는 SDK 0.147.0에서 캡처한 sanitized payload를 사용한다.

### B5. 같은 session의 execution 직렬화

문서는 같은 session에서 execution을 순차 실행하라고 요구하지만 `CodexSession.execute`와 `GeminiSession.execute`는 이를 강제하지 않는다. provider가 우연히 queue하거나 거절하는 동작에 의존하지 않는다.

각 session은 active execution reference를 원자적으로 관리한다. terminal이 확정되기 전 두 번째 `execute`는 host request를 보내지 않고 `IllegalStateException`으로 거절한다. terminal 확정과 다음 execute가 경쟁해도 정확히 하나만 active가 되게 한다. A5 `release()`도 같은 session gate를 사용한다.

**테스트**: overlapping execute 거절, terminal 직후 다음 execute 허용, cancel 요청만 보낸 상태에서는 여전히 거절, 서로 다른 session은 동시 실행 가능.

### B6. resume desired configuration

현재 문서의 "provider에 저장된 설정과 충돌하면 실패"는 너무 넓고, spec hash 비교는 잘못된 해결책이다. Codex는 resume 시 model, cwd, sandbox 등의 configuration override를 공식적으로 허용하며 일부 변경은 경고와 함께 적용한다.

포트 계약을 다음처럼 고친다.

> `resumeSession(id, spec)`의 `spec`은 과거 session 설정의 증명이 아니라 resume 이후 보존해야 할 desired configuration이다. adapter는 지원하는 override를 적용하고, 변경 불가능하거나 의미를 보존할 수 없는 필드만 path별 compatibility `ERROR`로 거절한다.

- spec hash를 `metadata`에 기록하거나 비교하지 않는다.
- process 재시작 뒤에도 같은 판정이 되도록 host의 in-memory spec map을 진실의 원천으로 삼지 않는다.
- provider가 저장된 설정을 조회할 수 있으면 immutable 항목 검증에 사용한다.
- provider가 새 ID를 정규화해 반환하면 요청 ID가 아니라 응답 ID로 `AgentSession.id`를 만든다.
- `provider-mapping.md`에 create/resume/turn별 override 가능 필드를 구분한다.

**테스트**: 지원 override 적용, 미지원 override 사전 거절, 새 process에서 resume, normalized response ID 사용.

### B7. metadata 계약 정리

`AgentSpec.metadata`와 `ExecutionOptions.metadata`는 현재 두 host에서 읽지 않으며 event/result/log에도 나타나지 않는다. "상관관계용"이라는 설명만 있고 관찰 가능한 의미가 없는 public API다.

0.1.0에서는 두 metadata 필드를 제거한다. 그 결과 `ExecutionOptions`가 비게 되면 타입과 `execute`의 options parameter도 함께 제거한다. 추후 trace/correlation 요구가 생기면 다음 중 하나를 명시한 별도 설계로 재도입한다.

- adapter log에만 적용되는 local correlation context
- provider에 전달되는 trace metadata
- event/result에 되돌아오는 execution tags

서로 다른 세 의미를 하나의 opaque map으로 다시 합치지 않는다.

**테스트/문서**: intent-projection의 metadata 축 제거, README 예제와 KDoc 동기화.

### B8. 느린 소비자가 이벤트를 잃고 `awaitResult()`가 영원히 매달림

**as-is** — `SdkBridge.kt`

```kotlin
private fun eventStream(executionId: String) =
    eventStreams.computeIfAbsent(executionId) {
        MutableSharedFlow(replay = 64, extraBufferCapacity = 256)
    }

// routeLine (stdout reader thread)
"event" -> eventStream(executionId).tryEmit(payload)   // 반환값 무시
```

- `BridgeAgentExecution`은 이 flow를 collect해 `mutableEvents.emit(event)`(suspend)로 소비자에게 전달한다.
- 소비자가 느리면 `emit`이 suspend → bridge 층 buffer(256)가 차면 `tryEmit`이 `false`를 반환하고 이벤트가 **조용히 사라진다**.
- terminal 이벤트가 사라지면 `updateLifecycle`이 실행되지 않아 `state`가 terminal이 되지 않고 `completion`이 완료되지 않는다. `awaitResult()`는 반환하지 않는다.
- `event-contract.md`는 소비자에게 "collect promptly"를 요구하지만, 손실은 소비자가 제어할 수 없는 어댑터 내부에서 발생한다.
- `eventStreams`는 execution 종료 후에도 제거되지 않는다.

**to-be**

원칙: **provider input ingestion, lifecycle/completion, public event delivery를 서로 다른 책임으로 분리한다.** public collector가 없거나 느려도 control-plane은 계속 진행해야 한다.

```text
process stdout reader
        │
        ▼
execution mailbox/actor ──> lifecycle state + completion (authoritative)
        │
        └───────────────> public event delivery (observability)
```

1. stdout reader는 event를 execution별 mailbox에 넣는다. mailbox send 실패를 절대 무시하지 않으며 등록되지 않은 execution, 이미 release된 execution, duplicate terminal을 protocol error로 기록한다.
2. execution actor는 raw event를 순서대로 map하고 **public flow emit을 기다리지 않은 채** state, pending interaction, final result를 갱신한다.
3. terminal outcome은 actor가 exactly once로 확정한다. terminal과 같은 raw payload에서 생성된 `ProviderEventObserved`를 terminal 뒤에 내보내지 않도록 mapper 결과를 terminal-last로 정규화하거나 terminal에서 iteration을 즉시 중단한다.
4. public `events`는 bounded observability 통로로 확정한다. 무제한 메모리로 영구 정지 subscriber까지 무손실 broadcast하지 않는다. 대신 active subscriber의 queue가 overflow하면 non-terminal event를 축약/제거하고 다음 gap을 명시적으로 전달한다.

   ```kotlin
   data class ObservationGap(
       override val executionId: ExecutionId,
       val droppedEvents: Long,
   ) : AgentEvent
   ```

   terminal event를 위한 자리는 항상 확보하며 gap 뒤 terminal을 전달한다. 늦게 붙은 collector에는 전체 과거 replay를 보장하지 않는다. 완전한 감사 로그가 필요하면 추후 sequence 기반 pull `ExecutionEventLog` capability를 도입한다.
5. 구현은 subscriber별 bounded queue 또는 sequence/ring-buffer 기반 flow로 한다. overflow는 `ObservationGap` 없이 조용히 일어나지 않는다. 어느 방식이든 terminal state와 `awaitResult()`는 event delivery queue와 독립적이며 `Channel.UNLIMITED` 하나로 문제를 숨기지 않는다.
6. terminal 후 execution mailbox와 bridge routing entry를 제거한다. `SdkBridge.release(executionId)`는 idempotent다.
7. 예상하지 못한 process exit/read failure는 모든 pending RPC뿐 아니라 모든 active execution을 `FAILED(TRANSPORT)`로 완료한다. event source의 정상 완료인데 terminal이 없었던 경우도 transport/protocol failure다.
8. 명시적 harness `close()`는 active execution에 cancel을 요청하고 짧은 bounded grace period를 준 뒤 `CANCELLED`로 완료한다. cancel 전달 자체가 실패하거나 process가 먼저 죽었으면 `FAILED(TRANSPORT)`가 승리할 수 있다.

**테스트**:

- collector가 없어도 terminal state와 `awaitResult()`가 완료된다.
- gate로 막은 느린 collector가 있어도 lifecycle completion이 먼저 끝난다. 실제 시간으로 `1,000 × 100ms`를 기다리는 테스트는 만들지 않는다.
- burst 중 terminal이 유실되지 않는다.
- process EOF, non-zero exit, invalid JSON, read failure가 모든 active execution을 `TRANSPORT`로 끝낸다.
- explicit close, close/cancel/completion race, duplicate terminal, terminal 없는 stream completion을 검증한다.
- terminal 뒤 semantic event가 없고 routing entry가 release된다.

---

## Part C. semantic-contract 기준 재서술

### as-is — `semantic-contract.md` "포트에 넣는 기준"

> 1. 호출자가 달성하려는 provider-neutral 목적은 무엇인가?
> 2. 두 agent가 그 목적을 실제로 수행하는가?
> 3. 결과와 진행을 공통 의미로 관찰할 수 있는가?
> 4. 어느 어댑터가 의미를 보존하지 못할 때 실행 전에 거절할 수 있는가?

실제 적용 결과가 이 기준과 맞지 않는다.

| 개념 | 어댑터 지원 | 현재 위치 | 기준 2 적용 시 |
|---|---|---|---|
| `ExecutionPolicy` | Codex만 | 포트 | 탈락해야 함 |
| `ContextPolicy.KeepWithinTokens` | 없음 | 포트 | 탈락해야 함 |
| interactive approval | Codex (Gemini는 SDK TODO) | capability로 제외 | — |

기준 2를 문자 그대로 적용하면 ExecutionPolicy도 빠져야 하고, 실제로는 기준 4(거절 가능)가 결정 기준으로 쓰였다. 기준을 실제 판단 방식에 맞춘다.

### to-be

```markdown
## 포트에 넣는 기준

어떤 개념을 기본 포트에 넣으려면 다음 다섯 질문에 모두 "예"여야 한다.

1. **provider-neutral 목적인가.** 호출자가 달성하려는 것이 특정 SDK의 수단이 아니라
   "agent를 이렇게 운용하고 싶다"는 의도로 서술되는가.
2. **core lifecycle/control-plane 목적인가.** session을 열고, agent loop를 실행하고,
   관찰·개입·종료·재개하는 모든 harness가 본질적으로 다뤄야 하는가.
3. **최소 한 어댑터가 공개·지원 API로 그 의미를 검증 가능하게 보존하는가.** wire protocol이나
   제품 UI에만 있고 현재 adapter SDK로 구현하지 못하면 아직 public port를 확정하지 않는다.
4. **결과와 진행을 공통 의미로 관찰할 수 있는가.**
5. **보존하지 못하는 어댑터는 실행 전에 거절할 수 있는가.** `validate`가 ERROR를 낼 수
   있어야 한다. 조용한 downgrade가 유일한 선택지라면 포트에 넣지 않는다.

기준 3은 "모든 어댑터"가 아니다. 지원이 비대칭인 core 목적은 포트에 두고 어댑터가 거절한다.
그래야 provider가 기능을 추가했을 때 포트가 아니라 어댑터의 validate만 넓어진다.

### 포트에 넣지 않는 것

- SDK 메서드 모양이 같다는 이유만으로 넣지 않는다.
- provider-neutral이라는 이유만으로 넣지 않는다. host tool 등록, structured output처럼
  embedding surface를 넓히는 선택 기능은 capability다.
- 특정 provider의 대화 관리·확장 메커니즘(fork, archive, MCP 관리)은 capability다.
```

### 재서술된 기준의 적용

| 개념 | provider-neutral | core | 구현 증명 | 관찰 | 거절 | 결정 |
|---|---|---|---|---|---|---|
| `ExecutionPolicy` | 예 | 예 | Codex | 예 | Gemini 거절 | 포트 유지 |
| interactive approval | 예 | 예 | **A1 spike 필요** | 예 | Gemini 거절 | spike 성공 시 포트. 실패 시 capability 후보 유지 |
| `ContextPolicy.KeepWithinTokens` | 예 | 예 | **없음** | — | — | 포트에서 제거 |
| `StopReason`, `FailureKind` | 예 | 예 | 양쪽 | 예 | 해당 없음 | 포트 |
| session runtime release | 예 | 예 | 양쪽 host map | 예 | 해당 없음 | 포트 (A5) |
| host-defined custom tool | 예 | 아니오: embedding 확장 | Gemini | 예 | Codex 거절 | capability |
| structured output | 예 | 경계선: 결과 shape 확장 | Codex | 예 | Gemini 거절 | capability 유지, 다음 minor 재검토 |
| steering | 예 | 필수 lifecycle 아님 | Codex | 예 | Gemini 거절 | capability |
| session history 조회 | 예 | 필수 lifecycle 아님 | 확인 필요 | 예 | 거절 가능 | capability 후보 |

`KeepWithinTokens`와 `ContextPolicy` 전체를 0.1.0 public API에서 제거한다. `ProviderManaged` 하나만 남은 sealed type은 소비자 의미 없이 확장 자리만 차지한다. `docs/capability-candidates.md`에 "provider가 caller-selected ceiling을 보장하면 새 정책과 함께 재도입" 조건을 기록하고 두 adapter의 validate 분기와 관련 테스트를 제거한다.

---

## Part D. contract test 확장

### as-is

| 모듈 | 테스트 | 검증 내용 |
|---|---|---|
| harness-codex | `CodexHarnessContractTest` ×2 | 성공 경로 1건, KeepWithinTokens 거절 |
| harness-codex | `CodexEventMapperTest` ×1 | 최종 메시지·완료 |
| harness-gemini-cli | `GeminiCliHarnessContractTest` ×2 | 성공 경로 1건, ReadOnly 거절 |
| harness-gemini-cli | `GeminiEventMapperTest` ×2 | content 누적, tool→effect 이중 발행 |
| harness-process-bridge | `BridgeProtocolTest` ×1 | JSON 설정 |
| harness-bundle | `HarnessesTest` ×1 | factory 반환 타입 |
| bridges | 없음 (`py_compile`, `node --check`만) | — |

- 두 contract test가 각자 `FakeBridge`를 복제하고 있다.
- **validate가 통과시킨 spec이 bridge JSON에 어떻게 실리는지 검사하는 테스트가 없다.** B1·B2·B3가 모두 여기서 새어 나갔다.
- lifecycle 규칙(순차 실행, 취소 경쟁, 느린 소비자)을 검사하는 테스트가 없다.
- Python/Node 번역 함수(`thread_arguments`, `agentOptions`)는 테스트가 없다.

### to-be

#### D1. 공유 테스트 모듈

`SdkBridge`는 `harness-process-bridge`가 소유하므로 `harness-protocol` test fixture가 이를 참조하면 production 의존 방향을 거스른다. 다음처럼 분리한다.

```text
harness-protocol/src/testFixtures/kotlin/dev/harnessprotocol/testing/
  ProtocolValueContract.kt    // 순수 public model 불변조건과 의미 fixture

harness-adapter-testkit/      // publish하지 않는 test-only 모듈
  RecordingBridge.kt          // SdkBridge fake: 요청 기록, 응답/EOF/실패 스크립트, 이벤트 주입
  AgentHarnessContractTest.kt // adapter가 provider event fixture만 제공하는 공유 contract
  IntentProjection.kt         // AgentSpec → 기대 bridge JSON 투영
  LifecycleScenario.kt        // close/cancel/terminal/interaction race 제어용 gate
```

`harness-adapter-testkit`은 `harness-protocol`과 `harness-process-bridge`에 의존하고 배포 대상에서 제외한다. 어댑터 모듈은 `testImplementation(project(":harness-adapter-testkit"))`로 받는다. 순수 protocol fixture만 `testImplementation(testFixtures(project(":harness-protocol")))`로 받을 수 있다.

#### D2. intent-projection 속성 테스트

**속성**: `harness.validate(spec).isCompatible`이면, `createSession(spec)`이 bridge에 보낸 `create_session` params는 spec의 모든 behavioral 필드를 정확히 담는다. 거절되었으면 요청이 발생하지 않는다.

```kotlin
abstract class AgentHarnessContractTest {
    abstract fun harness(bridge: SdkBridge): AgentHarness
    abstract fun projection(): IntentProjection   // adapter별 JSON 키 규칙

    @Test
    fun `compatible specs reach the bridge with their intent intact`() = runTest {
        for (spec in SpecSpace.all()) {                       // 아래 표 참고
            val bridge = RecordingBridge()
            val h = harness(bridge)
            val report = h.validate(spec)
            if (report.isCompatible) {
                h.createSession(spec)
                val sent = bridge.paramsOf("create_session").single()
                projection().assertPreserved(spec, sent)      // 필드별 존재/부재/값 검사
            } else {
                assertFailsWith<IncompatibleAgentSpecException> { h.createSession(spec) }
                assertTrue(bridge.paramsOf("create_session").isEmpty())
            }
        }
    }
}
```

`SpecSpace.all()`은 무작위가 아니라 전수 열거다(외부 property-testing 의존성 불필요).

| 축 | 값 |
|---|---|
| `instructions` | `null`, `""`, `"x"` |
| `model` | `null`, `"m"` |
| `workingDirectory` | `null`, `"/w"` |
| `skills` | `[]`, `[SkillReference("s", "/s")]` |
| `filesystem` | ProviderDefault, ReadOnly, WorkspaceWrite(∅), WorkspaceWrite({"/extra"}), FullAccess |
| `network` | 3값 |
| `approval` | 3값, A1 spike 성공과 A2 구현 후 4값 |

현재 최대 3×2×2×2×5×3×4 = 1,440 조합이며 fake bridge 단위 테스트로 감당 가능하다. 새 축을 추가해 Cartesian product가 급격히 커지면 모든 조합을 고집하지 않고 pairwise covering set과 명시적 edge case로 전환한다. `IntentProjection`은 production 변환 코드를 호출하지 않는 독립적인 선언이어야 한다.

`IntentProjection.assertPreserved` 규칙 (Codex 예):

| spec | 기대 JSON |
|---|---|
| `instructions == null` | key 없음 |
| `instructions == ""` | `"instructions": ""` |
| `filesystem == ProviderDefault` | `"filesystem": "provider_default"` |
| `approval == PROVIDER_DEFAULT` | `"approval": "provider_default"` (host가 이걸 SDK 인자로 바꾸지 않는지는 D4) |
| `network != PROVIDER_DEFAULT` | validate가 통과했다면 `filesystem == "workspace_write"` 여야 함 (B3) |

이 한 테스트가 B1(호스트 쪽 D4와 함께)·B2·B3와, 앞으로 추가되는 모든 spec 필드의 누락을 잡는다.

#### D3. lifecycle contract test (공유 abstract class에 추가)

| 테스트 | 검증 |
|---|---|
| `rejects overlapping execute on one session` | 첫 execution terminal 전 두 번째 `execute` → `IllegalStateException`; host request는 추가되지 않음 |
| `state is terminal before awaitResult returns` | `awaitResult` 후 `state.value ∈ terminal` |
| `completes without an event collector` | event collector가 없어도 `awaitResult` 완료 |
| `cancel after terminal is a no-op` | bridge에 `cancel_execution` 요청이 가지 않음 |
| `completion wins the race against cancel` | cancel 요청 후 completed 이벤트 → `COMPLETED`, `awaitResult` 성공 |
| `slow collector does not block lifecycle` | gate로 public collector를 막아도 terminal state/결과가 확정됨 |
| `overflow is explicit and terminal survives` | bounded queue overflow → `ObservationGap` → terminal; 조용한 drop 없음 |
| `process exit fails every active execution` | EOF/non-zero/read failure → `FAILED(TRANSPORT)` |
| `close settles every active execution` | bounded cancel 후 모든 waiter가 완료되고 interaction/routing entry 정리 |
| `terminal is exactly once and last` | duplicate terminal 무시/기록, terminal 뒤 semantic event 없음 |
| `interaction request suspends and respond resumes` | A1: `WAITING` → `respond` → `respond_interaction` 요청 기록 → `RUNNING` |
| `interaction can be cleared without a response` | provider timeout/turn interruption → `Cleared`, pending snapshot 비움 |
| `rejects invalid or duplicate interaction response` | 종류/decision/ID 검증 후 provider request 0건 |
| `caller decides is rejected where unsupported` | A2: Gemini에서 ERROR |
| `failure kind is preserved` | A3: 각 provider 실패 이벤트 → 기대 `FailureKind`, `AgentExecutionFailedException.kind` |
| `turn limit completes with stop reason` | A4: Gemini `max_session_turns` → `COMPLETED`, `stopReason == TURN_LIMIT` |
| `usage is cumulative per execution` | B4: 두 execution 순차 실행 후 두 번째 usage에 첫 번째가 섞이지 않음 |
| `release is called after terminal` | B8: `RecordingBridge.released == [executionId]` |
| `session release is idempotent` | A5: host map 정리, release 후 execute 거절, durable ID resume 가능 |

interaction 관련 contract test는 A1 spike가 성공한 경우에만 suite에 추가한다. 실패한 spike를 보상하려고 항상 skip되는 public contract test를 만들지 않는다.

#### D4. host script 테스트

| 위치 | 도구 | 테스트 |
|---|---|---|
| `bridges/tests/test_codex_bridge.py` | pytest, `openai_codex`는 `sys.modules` stub | 최종 SDK params의 approval 필드 부재 또는 사전 ERROR(B1), network 표현 범위(B3), null instructions 생략, skills → activation marker + `SkillInput`, nested token usage fixture |
| `bridges/tests/gemini_bridge.test.mjs` | `node --test`, SDK는 stub module | `agentOptions`: null instructions 생략(B2), skills → `skillDir`, `sdkSpecifier` Windows 경로 처리 |

Gradle에서 `./gradlew check`가 `bridges` 테스트도 실행하도록 `Exec` task를 root build에 추가한다. 로컬 개발에서는 Python/Node 부재 시 명시적 skip을 허용하지만, CI와 release verification에서는 runtime 부재를 실패로 처리한다. "skip + 경고" 상태로 release할 수 없게 별도 strict flag/task를 둔다.

#### D5. 문서

`docs/testing.md`(신규): contract test 상속 방법, `SpecSpace` 확장 규칙("AgentSpec에 필드를 추가하면 `SpecSpace`와 `IntentProjection`에 같은 커밋에서 추가"), host 테스트 실행 방법.

---

## Part E. 실행 순서

테스트를 먼저 red로 만들고 바로 대응 수정으로 green을 만든다. red 상태는 로컬 구현 중에만 허용하며 완결된 commit과 `main`에는 남기지 않는다. 의미 기준과 SDK 가능성 확인을 public signature보다 먼저 수행한다.

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 1 | Part C 기준 확정, B1 `PROVIDER_DEFAULT`, B6 resume 의미 결정, `KeepWithinTokens`/단일 `ContextPolicy`와 B7 metadata 제거 결정 반영 | semantic/protocol 문서가 하나의 의미를 말함 |
| 2 | D1 `harness-adapter-testkit`, D2 intent-projection, D4 host 테스트 골격 | 각 결함이 로컬에서 red로 재현되고 같은 change set 안에서 다음 단계 준비 |
| 3 | B8 control-plane/data-plane 분리, process death/close/terminal 규칙 | collector 유무·속도와 무관하게 모든 execution waiter가 bounded time 안에 완료 |
| 4 | B5 session 직렬화, A5 session release | server형 반복 create/release에서 host map이 증가하지 않고 race test green |
| 4a | A1-8a 저수준 client 이관 viability spike | B1 선호/fallback 확정, A1 진행 여부 확정 |
| 5 | B1(handler 정책 표 포함), B2, B3 수정 | 호환 spec의 의미가 최종 SDK 인자까지 보존되고 미지원 spec은 request 전 ERROR; 어떤 정책에서도 자동 accept 없음 |
| 6 | B4 usage와 Codex nested payload 수정 | 실제 fixture 및 두 연속 execution contract test green |
| 7 | A3 `FailureKind`, A4 `StopReason`/`WarningKind`, `WorkStatus.CANCELLED`(#12), finalMessage FINAL-only(#10), skill envelope 문구(#5/#35) | 구조화된 provider code 기반 분류와 terminal/result 일치 |
| 8 | A1-8b approval round-trip spike (4a 성공 시) | handler unblock → interrupt 순서로 cancel/close 포함 end-to-end 성공 또는 명시적 실패 기록 |
| 9 | spike 성공 시에만 A2 `CALLER_DECIDES`와 A1 interaction을 port→bridge→host→mapper에 한 번에 추가 | 자동 cleanup·다중 pending·invalid response·실제 수동 승인 test green |
| 10 | 전체 문서 동기화 (`protocol-reference`, `event-contract`, `lifecycle-and-concurrency`, `provider-mapping`, `semantic-contract`, README) | 모든 Kotlin snippet과 상태/예외/지원 표가 코드와 일치 |
| 11 | `clean test`, strict host tests, `git diff --check`, publication smoke test | skip 없음, 모든 검증 green |
| 12 | `publicationVersion` → `0.1.0` | release checklist 완료 |

4a 또는 8단계 spike가 실패하면 9단계를 건너뛴다. 이 경우 `CALLER_DECIDES`를 enum에 넣지 않고 interactive intervention을 `capability-candidates.md`에 실패 원인과 재검토 trigger와 함께 남긴다. 실패한 provider 구현을 전제로 public API만 먼저 배포하지 않는다.

---

## Post-POC 이슈

원래 리뷰에서 발견된 이슈의 추적표다. v2에서 Part A/B로 승격된 항목도 원래 번호를 유지해 어디에서 처리하는지 적는다. 우선순위는 P0(0.1.0 blocker) / P1(0.1.x) / P2(다음 minor) / P3(기록)이다.

### 계약·의미

| # | P | 이슈 | 위치 | 제안 |
|---|---|---|---|---|
| 5 | P0 | `protocol-reference.md`의 "입력을 덧붙이지 않는다"와 명시적 skill activation marker가 모순된다. 현재 `AgentSpec.skills`는 available이 아니라 매 실행 명시적 activation을 뜻하므로 구현 자체는 계약과 맞다. Codex도 `$skill` + skill input을 공식 방식으로 사용한다. | 두 host와 protocol 문서 | 입력의 semantic user text는 보존하되 provider가 요구하는 activation envelope를 추가할 수 있다고 문서를 고친다. "available only" 요구가 실제로 생길 때만 `SkillActivation`을 별도 설계한다. |
| 6 | P0 | 같은 session의 overlapping execute를 막지 않는다 | 두 session 구현 | **B5로 승격.** atomic active-execution gate와 race test. |
| 7 | P0 | resume spec 의미가 불명확하다 | 두 host와 lifecycle 문서 | **B6로 승격.** desired configuration으로 정의하고 spec hash 비교는 하지 않는다. |
| 9 | P2 | `MessageDelta.phase`가 어댑터에서 항상 `PROGRESS`다. 소비자는 최종 답변을 스트리밍할 수 없다 | 두 매퍼 | Codex는 delta에 item id가 있으면 `item/started`의 phase를 기억해 적용. Gemini는 구분 불가 → delta에서 `phase`를 제거하고 `MessageCompleted`에만 두는 안을 검토 |
| 10 | P0 | Codex `finalMessage`가 마지막 완료 `agentMessage`로 덮어써진다. 마지막 메시지가 commentary면 finalMessage가 commentary가 된다 | `CodexEventMapper.mapItem` | `phase == FINAL`인 completed message만 canonical candidate로 채택. final completed snapshot이 없을 때만 같은 final stream의 delta 누적값을 사용한다. message ID/phase 상관관계를 fixture로 검증한다. |
| 11 | P2 | `EffectKind.CONTEXT_MANAGEMENT`와 `ContextManaged` 이벤트 중복 | `Events.kt` | `EffectKind.CONTEXT_MANAGEMENT` 제거 |
| 12 | P0 | Gemini tool `cancelled` → `WorkStatus.FAILED`. `DECLINED`와도 다른 정보가 사라진다 | `GeminiEventMapper.statusOr` | `WorkStatus.CANCELLED` 추가 |
| 13 | P2 | `IncompatibleAgentSpecException extends IllegalArgumentException`. 값 불변조건 위반과 의미 비호환을 소비자가 같은 catch로 잡는다 | `Compatibility.kt` | `RuntimeException` 직계로 변경 |
| 14 | P3 | `AgentHarness.close() = Unit` 기본 구현. 구현체가 override를 잊어도 통과 | `Harness.kt` | 기본 구현 제거 |
| 15 | P3 | Gemini `execution_started`는 host가 `sendStream` 직전에 합성. `RUNNING`이 provider 상태를 반영하지 않는다 | `gemini_cli_sdk_bridge.mjs` | 계약 위반은 아님. `provider-mapping.md`에 기록 |
| 16 | P0 | `ExecutionOptions.metadata`, `AgentSpec.metadata`가 두 host에서 사용되지 않고 버려진다 | 두 host | **B7로 승격.** 0.1.0에서는 제거하고 향후 trace 의미를 구체화한 뒤 재도입한다. |

### 원래 포트 확장 후보와 v2 처리 상태

| # | P | 이슈 | 제안 |
|---|---|---|---|
| 17 | P0 | **Session 해제가 없다.** 서버형 wrapper는 harness 하나로 다수 session을 만들고, 두 host의 `sessions` Map은 영원히 커진다 | **A5로 승격.** 동기 `close()`가 아니라 `suspend fun release()`와 bridge `release_session`을 추가한다. |
| 18 | P2 | resume 후 대화 이력 조회 불가. `events`는 replay 계약이 없어 대체 불가 | `SessionHistory` capability: `AgentSession.history(): List<HistoryEntry>`. Codex thread read 지원 여부, Gemini SDK 지원 여부를 먼저 조사 |
| 19 | P2 | `AgentInput`이 `Text`뿐. host가 `LocalImageInput`을 import만 한다 | `AgentInput.Image(path)`, `AgentInput.Composite(parts)` 추가. Gemini `sendStream`이 string만 받으면 validate/execute에서 거절 |
| 20 | P2 | per-turn 예산(최대 tool call 수, 토큰 예산)이 없다 | provider가 turn 단위 한도를 노출하는지 조사 후 의미 있는 첫 필드와 함께 `ExecutionOptions` 재도입을 검토. 없으면 애플리케이션이 `StopReason`/usage로 관리 |
| 21 | P2 | structured output | capability `StructuredOutputCapability`. Codex는 지원, Gemini는 거절 |
| 22 | P3 | session 단위 usage 조회 (`AgentSession.usage()`) | B4의 `sessionUsage`가 이벤트로 오므로 당장은 불필요. provider가 조회 API를 주면 추가 |
| 23 | P3 | timeout 모델 부재 — 현재 "애플리케이션 책임" | 유지. 다만 `withTimeout` + `cancel()` helper를 `harness-protocol`의 확장 함수로 제공하면 반복 코드가 준다 |

### 구현·운영

| # | P | 이슈 | 위치 | 제안 |
|---|---|---|---|---|
| 24 | P0 | **host process 하나가 죽으면 모든 active execution이 매달릴 수 있다.** `failPending`은 RPC만 실패시킨다. | `JsonLineProcessBridge` | **B8로 승격.** 모든 active execution을 `FAILED(TRANSPORT)`로 settle하고 재시작 정책을 문서화한다. session별 process 격리는 이번 범위가 아니다. |
| 25 | P2 | `WorkId` fallback이 `"unknown"`, `"command"`, `"tool"`처럼 상수라 서로 다른 work가 같은 ID로 충돌할 수 있다 | 두 매퍼 | provider ID가 없으면 매퍼가 execution 내 단조 증가 ID를 발급하고 `Warning(OTHER)`로 알린다 |
| 26 | P2 | Gemini `changedPaths`를 `args.file_path/path/absolute_path` 키 이름 휴리스틱으로 추출 | `GeminiEventMapper.mapToolEvents` | tool 이름별 인자 스키마를 표로 고정하고 `provider-mapping.md`에 기록. `apply_patch`처럼 여러 파일을 바꾸는 tool은 result에서 추출 |
| 27 | P2 | `EffectKind` 매핑이 tool 이름 문자열 목록(`run_shell_command`, `write_file`…)에 묶여 있어 Gemini CLI 버전에 따라 조용히 깨진다 | `GeminiEventMapper.effectKind` | 매핑 표를 `provider-mapping.md`에 두고 매퍼 테스트가 표와 동기화되게 한다. 미매핑 tool은 `EffectKind.OTHER`가 아니라 `ToolCallChanged`만 발행(현재와 동일)하되 `ProviderEventObserved`로 추적 |
| 28 | P0 | harness `close()`가 진행 중 execution을 settle하지 않고 process를 destroy한다 | 두 harness | **B8/A5로 승격.** bounded cancel grace 뒤 명시적 close는 cancellation으로, 예기치 않은 process death는 transport failure로 완료한다. |
| 29 | P3 | `EmbeddedBridgeResource.extract`가 launch마다 임시 파일을 만들며 JVM 종료 전까지 남는다 | `EmbeddedBridgeResource.kt` | 현재 `deleteOnExit`은 이미 있다. 반복 launch 비용이 실제 문제일 때 concurrency-safe content-hash cache를 검토한다. |
| 30 | — | `jsonable`이 무조건 `str`로 구조를 잃는다는 지적 | `codex_sdk_bridge.py` | **현재 코드 확인으로 종료.** 이미 `BaseModel.model_dump`, `dataclasses.asdict`, collection, `vars`, 최종 `str` 순서다. `vars()`를 앞세우면 오히려 typed serialization을 악화시킨다. |
| 31 | P3 | `Harnesses.create(provider)`가 문자열 switch. 제3 provider를 소비자가 등록할 수 없다 | `Harnesses.kt` | `ServiceLoader` 기반 `AgentHarnessFactory` 또는 `Harnesses.register(ProviderId, () -> AgentHarness)` |
| 32 | P0 | host error/turn completion이 둘 다 terminal이 될 수 있고, mapper는 terminal과 같은 list 뒤에 `ProviderEventObserved`를 붙인다. 현재 `takeWhile`은 list 내부 iteration을 끊지 않아 terminal 뒤 event가 나올 수 있다. | host, mapper, bridge protocol | **B8로 승격.** execution actor가 exactly-one terminal을 정하고 terminal-last를 강제한다. `docs/bridge-protocol.md`에 NDJSON schema, EOF, duplicate/late event 규칙을 명문화한다. |
| 33 | P0 | resume 후 Kotlin이 provider가 반환한 정규화 ID를 무시한다 | 두 host/Kotlin adapter | **B6로 승격.** 응답 `sessionId`를 사용한다. |
| 34 | P3 | `.venv`, `.gradle`, `.kotlin`, `build`가 OneDrive 폴더 안에 있어 잠금 문제가 있다는 주석이 build.gradle.kts에 있다. `.venv`는 gitignore되지만 `bridges/__pycache__`도 저장소에 생성된다 | 저장소 | 문서화만. 이미 `build`는 tmp로 옮겨져 있음 |

### 문서

| # | P | 이슈 | 제안 |
|---|---|---|---|
| 35 | P0 | `protocol-reference.md`의 "입력을 변조하지 않는다"와 provider skill activation envelope의 모순 | #5와 함께 semantic text 보존과 provider envelope를 구분해 정정 |
| 36 | P0 | `event-contract.md`의 "즉시 collect"가 adapter 내부 손실 책임을 소비자에게 넘기는 것처럼 읽힌다 | B8과 함께 정정. 즉시 수집은 완전한 관찰을 위한 권장사항으로 남기되 lifecycle 정확성의 전제는 아니라고 명시한다. 느린 subscriber에 대한 bounded delivery/overflow 계약도 함께 적는다. |
| 37 | P3 | README의 "검증" 절이 `py_compile`/`node --check`만 안내. D4 이후 host 테스트 명령 추가 | D5와 함께 |
| 38 | P3 | `codex-agent-adoption-review.md`의 재검토 트리거에 interaction 비교가 없다 | A1 SDK spike 또는 interaction 구현 후 `codex-agent`의 approval/elicitation API와 비교하도록 추가 |

---

## 변경 요약 (포트 시그니처 diff)

```text
ExecutionState            + WAITING (A1 spike 성공 시)
ApprovalPolicy            + CALLER_DECIDES (A1 spike 성공 시)
ContextPolicy             타입/AgentSpec 필드 전체 제거
AgentSpec                 - metadata
ExecutionOptions          metadata 제거; 빈 타입이면 options parameter와 함께 제거
AgentSession              + suspend release()
AgentEvent                + InteractionRequested, InteractionResolved (조건부)
AgentEvent                + ObservationGap
AgentEvent.ExecutionFailed + kind: FailureKind
AgentEvent.Warning         + kind: WarningKind
AgentEvent.UsageChanged    usage → execution, + session
AgentResult                + stopReason, + sessionUsage
AgentExecution             + pendingInteractions, + respond() (조건부)
AgentExecutionException    sealed → AgentExecutionFailedException(kind), AgentExecutionCancelledException
(new)                      FailureKind, StopReason, WarningKind
(new, 조건부)              InteractionId, InteractionRequest.Approval, InteractionResponse, InteractionResolution, ApprovalDecision, ClearReason
WorkStatus                + CANCELLED
AgentSession.execute      options parameter 제거
SdkBridge (internal)       + release(executionId), release_session
bridge protocol (internal) + lifecycle failure/EOF 규칙; interaction 메시지는 조건부
```

조건부 항목은 A1 공개 SDK spike가 통과하지 않으면 0.1.0 diff에서 제외한다.
