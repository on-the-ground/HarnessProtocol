package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

/**
 * 공개 Port의 적합성 검사가 adapter의 실제 경계를 구동하기 위한 계약.
 *
 * Profiles와 기대값은 adapter.support 또는 validate의 결과를 복사해 만들지 않는다. 고정한
 * runtime 구성에 대한 독립 선언이다. 제어 명령은 native 경계에 사실을 유도하고, observation은
 * 실제 전달·실행을 기록한다. AgentTask의 state/outcome을 직접 조작해 통과시키면 부적합하다.
 *
 * 검사는 provider wire·SDK·bridge를 알지 않는다. 실제 모델 호출도 요구하지 않는다.
 */
interface HarnessFixture {
    val provider: ProviderId

    /** 조건별 구성과 지원·거절·미확인 요청 사례. 정상 기본 작업을 수락하는 사례가 반드시 있다. */
    fun profiles(): List<FixtureProfile>

    /** 같은 profile은 같은 저장 namespace를 사용하되 독립된 harness handle을 만든다. */
    fun createHarness(profileId: String): AgentHarness

    /** startTask 반환 직후 제어 가능하며 기본 fixture는 명시한 종결 전까지 작업을 유지한다. */
    fun control(task: AgentTask): TaskControl
    fun control(session: AgentSession): SessionControl
    fun control(harness: AgentHarness): RuntimeControl
    fun controlNextStart(session: AgentSession): StartControl
}

/** 조건부 지원을 boolean 집합으로 축소하지 않는다. profile별 유효 조건은 description에 명시한다. */
data class FixtureProfile(
    val id: String,
    val description: String,
    val expectedSupport: SupportReport,
    val cases: List<RequirementCase>,
) {
    init {
        require(id.isNotBlank())
        require(description.isNotBlank())
        require(cases.isNotEmpty())
        require(cases.map { it.id }.distinct().size == cases.size)
    }
}

/**
 * 검증자가 그대로 전달할 구체적 요구와 기대 판정.
 *
 * 사전 validate가 미확인이어도 호출 경계에서 추가 확인해 수락할 수 있으므로 두 기대를 분리한다.
 * create가 수락되는 사례에서만 task 판정을 검사한다. 실제 호출의 UNCONFIRMED는
 * RequirementUnconfirmedException이며 요청 수락 확인 유실은 StartControl로 별도 유도한다.
 */
data class RequirementCase(
    val id: String,
    val sessionSpec: SessionSpec,
    val request: TaskRequest,
    val sessionValidation: CompatibilityStatus,
    val taskValidation: CompatibilityStatus,
    val createDecision: CompatibilityStatus = sessionValidation,
    val startDecision: CompatibilityStatus = taskValidation,
    val capability: Capability? = null,
) {
    init { require(id.isNotBlank()) }
}

/**
 * Runtime이 제공한 산출물 사실. 검증자가 provider의 schema 검증을 가정하지 않도록 구별한다.
 * NOT_VALIDATED인 구조화 결과는 요청에 따라 adapter가 검증해야 한다.
 */
sealed interface OutputObservation {
    val complete: Boolean

    data class Text(val text: String, override val complete: Boolean = true) : OutputObservation
    data class Structured(
        val json: String,
        override val complete: Boolean = true,
        val reportedValidation: SchemaValidation = SchemaValidation.NOT_VALIDATED,
    ) : OutputObservation
}

/**
 * 실제 허용 대상 집합으로 준비한 세션 권한. description만 비교해서 효과를 허용하지 않는다.
 * targetKey는 합성 업무 자원의 identity이며 provider path·wire 형식이 아니다.
 */
data class PermissionScenario(
    val grant: SessionApprovalGrant,
    val coveredTargets: Set<String>,
) {
    init {
        require(coveredTargets.isNotEmpty())
        require(coveredTargets.all { it.isNotBlank() })
    }
}

/**
 * 명령은 native 경계에 도달한 뒤 반환하며 승인 응답이나 Task 종결까지 기다리지 않는다.
 * 결과는 public Port에서 관찰한다. 입력·효과 observation은 그 Port의 값에서 역산하지 않는다.
 */
interface TaskControl {
    suspend fun reportRunning()
    suspend fun reportMessageDelta(messageKey: String, text: String, role: MessageKind? = null)
    suspend fun reportMessageCompleted(messageKey: String, text: String, role: MessageKind? = null)

    /** Runtime이 실제로 받은 이번 작업의 입력. caller의 TaskRequest를 그대로 되돌려주면 안 된다. */
    fun observedInput(): TaskInput

    /** Runtime에 실제 적용된 지속 지시. null과 명시적 빈 지시를 구별한다. */
    fun observedInstructions(): String?
    fun observedActivatedSkills(): Set<String>

    /**
     * 실제 후속 모델 요청·runtime 문맥에서 해당 내용을 사용할 수 있었는가.
     * spec, 요청 이력 목록, 테스트가 주입한 예정된 답변만 검색해서 true로 보고하면 안 된다.
     */
    fun observedContextContains(text: String): Boolean

    /** 같은 workKey는 같은 실제 호출이다. effect가 null이면 효과의 근거가 없는 도구다. */
    suspend fun reportToolCall(workKey: String, name: String, effect: EffectKind? = null)
    suspend fun reportToolResult(workKey: String, result: String? = null, failed: Boolean = false)

    /**
     * 승인 전 효과가 없고 세션 grant가 coveredTargets에만 적용되는 실제 합성 업무 연산을 유도한다.
     * permission을 제공하면 native 승인 범위도 그 대상 집합에 맞춰 구성해야 한다.
     * 같은 scopeId로 대상 집합을 바꾸지 않는다. 다른 범위의 targetKey에는 별도 승인이 필요하다.
     */
    suspend fun attemptGuardedEffect(
        workKey: String,
        targetKey: String,
        prompt: String,
        effect: EffectKind,
        decisions: Set<ApprovalDecision> = setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE),
        permission: PermissionScenario? = null,
    )

    /** 이벤트 개수가 아니라 실제 업무 변경 횟수다. 비협조적 작업에도 workKey를 사용한다. */
    fun observedEffects(workKey: String): Int

    suspend fun askQuestion(
        prompt: String,
        choices: List<String> = emptyList(),
        allowsFreeForm: Boolean = choices.isEmpty(),
    )
    suspend fun withdrawOpenRequests()
    suspend fun supersedeRequest(interactionId: InteractionId)
    fun controlNextResponse(interactionId: InteractionId): ResponseControl

    /**
     * 이 Task의 해당 시점 누적값 전체를 보고한다. 반복 보고해도 더하지 않는다.
     * null 필드는 미측정이며 이전 값을 유지하라는 patch 명령이 아니다.
     */
    suspend fun reportUsageSnapshot(task: AgentUsage, session: AgentUsage? = null)

    /**
     * 겹치지 않는 다음 실행 구간의 증분을 보고한다. 미측정 구간을 합산하면 그 필드는 unknown이다.
     * fixture는 지원하는 native 표현으로 전달하되 입력 사실의 의미를 바꾸지 않는다.
     */
    suspend fun reportUsageDelta(delta: AgentUsage)

    /** 종결 전에 확보한 업무 산출물. 이후 실패·취소·미확정에서도 회수되는지 검사한다. */
    suspend fun reportOutput(output: OutputObservation)

    /** null은 추가 산출물 없음이다. 앞서 확보한 output을 지우지 않는다. */
    suspend fun reportCompletion(
        output: OutputObservation? = null,
        stopReason: StopReason = StopReason.FINISHED,
    )

    /** kind가 null이면 분류 근거 없이 설명만 있는 실패다. 자연어로 종류를 추측하면 안 된다. */
    suspend fun reportFailure(message: String, kind: FailureKind? = null)
    suspend fun reportCancelledTermination()

    /** 내부 단위만 종료하며 Task는 계속 실행한다. */
    suspend fun endInnerTurnOnly()
    suspend fun dropObservationWithoutTerminal()

    /** 해당 작업은 취소에 협조하지 않고 명시적인 release 전까지 실제 실행 상태로 남는다. */
    suspend fun leaveUncooperativeWork(workKey: String)
    suspend fun releaseUncooperativeWork(workKey: String)

    /** 명령 수락 시점부터 지연 뒤 종결 근거를 전달한다. Task 상태를 직접 덮어쓰지 않는다. */
    suspend fun scheduleCompletionAfter(millis: Long, output: OutputObservation? = null)

    /** 지원한 진단 경로로만 전달한다. 의미 이벤트 queue의 overflow를 유발하면 안 된다. */
    suspend fun reportDiagnostic(name: String, payload: String)
}

enum class MessageKind { ANSWER, COMMENTARY, EXPLANATION }

interface StartControl {
    fun accept()
    fun rejectBeforeDelivery(message: String)

    /** 외부에서는 수락 여부를 모른다. fixture는 실제 수락 여부를 독립적으로 관찰할 수 있다. */
    fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean)
    fun observedSubmissions(): Int
    fun observedAcceptedStarts(): Int
}

interface ResponseControl {
    fun accept()
    fun rejectBeforeDelivery(message: String)
    fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean)

    /** Native 경계의 실제 시도·수락 기록. pending snapshot이나 resolved 이벤트에서 계산하지 않는다. */
    fun observedSubmissions(): Int
    fun observedAcceptedResponses(): List<InteractionResponse>
}

interface SessionControl {
    /** 다음 실제 저장·재개 연산을 실패시킨다. public outcome을 직접 지정하지 않는다. */
    fun failNextPersistenceWrite(message: String)
    fun failNextReopen(message: String)

    /** 저장소가 같은 문맥의 재개에 정규화한 참조를 반환하게 한다. 새 문맥 생성이 아니다. */
    fun canonicalizeNextReopenAs(ref: PersistentSessionRef)
}

interface RuntimeControl {
    /** 실행 귀속 범위에 맞는 사실만 판정 근거가 된다. */
    suspend fun killRuntime(ownsRunningWork: Boolean)

    /** 수락 뒤 환경 변화와 이후 요구 거절을 검사한다. */
    suspend fun revokeSupport(capability: Capability)

    /**
     * 선언한 process 재시작 범위를 실제로 넘는다. 단순 harness 재생성을 이 검사로 대신하지 않는다.
     * 그 보장을 요구한 profile을 제공할 때 실행하며, 재시작 뒤 새 harness는 같은 저장소에 연결한다.
     */
    suspend fun restartProcessBoundary()
}
