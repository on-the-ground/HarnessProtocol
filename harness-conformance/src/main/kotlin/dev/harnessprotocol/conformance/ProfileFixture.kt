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
interface ProfileFixture {
    val provider: ProviderId

    /** 조건별 구성과 지원·거절·미확인 요청 사례. 정상 기본 작업을 수락하는 사례가 반드시 있다. */
    fun profiles(): List<FixtureProfile>

    /** 같은 profile은 같은 저장 namespace를 사용하되 독립된 harness handle을 만든다. */
    fun createHarness(profileId: String): AgentHarness
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
