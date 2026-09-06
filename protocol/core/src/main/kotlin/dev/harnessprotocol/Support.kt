package dev.harnessprotocol

import kotlin.time.Duration

/**
 * 선택 계약의 지원 정보.
 *
 * 지원 정보는 소비자가 구성을 고르고 요청을 준비하는 데 쓰는 정보이지 이후 요청의 이행 보장이
 * 아니다. 조회하지 않고도 요구를 명시할 수 있으며 실제 보장은 [AgentHarness.validate]와 호출
 * 경계의 수락이 담당한다.
 */
data class SupportReport(
    val entries: Map<Capability, Support>,
) {
    operator fun get(capability: Capability): Support =
        entries[capability] ?: Support.Unknown("adapter가 이 계약에 대한 정보를 제공하지 않는다")
}

enum class Capability {
    CALLER_APPROVAL,
    QUESTIONS,
    PERSISTENCE,
    WORKSPACE,
    EXECUTION_CONSTRAINT,
    STRUCTURED_OUTPUT,
    DIAGNOSTICS,
}

/** 한 계약의 지원 상태. [Unknown]을 이행 가능으로 단정하지 않는다. */
sealed interface Support {
    /** 이 harness 구성에서 지원한다. 개별 요청의 수락은 여전히 호출 경계가 판정한다. */
    data object Supported : Support

    /**
     * 조건이 맞을 때만 지원한다.
     *
     * @property scope 조건이 적용되는 범위
     * @property condition 사람이 읽을 수 있는 조건 설명
     */
    data class Conditional(val scope: SupportScope, val condition: String) : Support

    data class Unsupported(val reason: String) : Support

    /** 정적 정보만으로 판단할 수 없다. 인증·자원 상태 등은 확인했다고 주장하지 않는다. */
    data class Unknown(val reason: String) : Support
}

enum class SupportScope {
    /** adapter 종류에 대한 정적 사실. */
    PROVIDER,
    /** 현재 harness 구성·환경에 종속된다. */
    HARNESS,
    /** session 설정에 종속된다. */
    SESSION,
}

/**
 * 정리 연산의 시간 상한. 호출자가 사용 전에 알 수 있어야 한다.
 *
 * 유예 만료는 취소 성공이 아니며, 상한을 넘기면 [TaskOutcome.Unresolved]로 종결한다.
 *
 * @property perTask 작업 하나의 종료를 확인하는 데 쓰는 상한
 * @property total close/release 한 번의 전체 상한
 * @property aggregatesAcrossResources 여러 자원의 유예가 순차 합산되는지
 */
data class CleanupBudget(
    val perTask: Duration,
    val total: Duration,
    val aggregatesAcrossResources: Boolean,
)
