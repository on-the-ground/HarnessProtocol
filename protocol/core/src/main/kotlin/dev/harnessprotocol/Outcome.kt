package dev.harnessprotocol

/**
 * 한 Task handle의 종결 판정.
 *
 * 네 판정 모두 확정 시점까지 확보한 산출물과 사용량을 전달한다. 이 회수는 event 구독 여부와
 * 무관하다. 판정 근거는 docs/lifecycle-and-concurrency.md 의 종결 확인 규칙을 따른다.
 */
sealed interface TaskOutcome {
    val taskId: TaskId

    /** 확정 시점까지 확보한 업무 산출물. 모든 내부 도구 payload를 담지 않는다. */
    val output: TaskOutput?

    /** 이 Task의 누적 사용량. 측정하지 못한 필드는 `null`이며 0으로 합성하지 않는다. */
    val usage: AgentUsage

    /** provider가 session 누적을 보고할 때만 존재한다. */
    val sessionUsage: AgentUsage?

    /**
     * 실행 종료와 결과를 확인했다. 업무 목표 달성이나 schema 검증의 증명은 아니다.
     *
     * [output]이 null이면 확보한 업무 산출물이 없다. 실제로 받은 빈 텍스트와 구별하며 산출물이
     * 없다는 이유로 확인된 완료를 실패·미확정으로 바꾸지 않는다. [stopReason]이 [StopReason.FINISHED]가
     * 아니면 agent가 스스로 끝낸 것이 아니라 한도·반복 감지·provider 중단이다.
     */
    data class Completed(
        override val taskId: TaskId,
        override val output: TaskOutput? = null,
        val stopReason: StopReason,
        override val usage: AgentUsage = AgentUsage.Unknown,
        override val sessionUsage: AgentUsage? = null,
    ) : TaskOutcome

    /**
     * 작업 실패를 확인했다. 통신 단절만 관찰했다면 이 판정을 쓰지 않는다.
     *
     * @property kind 후속 판단에 필요한 분류. 구조화된 provider 정보·의미가 확인된 native
     * 예외·직접 확인한 runtime 사실로만 확정하고 자연어 문구의 추측으로 바꾸지 않는다
     */
    data class Failed(
        override val taskId: TaskId,
        val kind: FailureKind,
        val message: String,
        val cause: Throwable? = null,
        override val output: TaskOutput? = null,
        override val usage: AgentUsage = AgentUsage.Unknown,
        override val sessionUsage: AgentUsage? = null,
    ) : TaskOutcome

    /** 취소에 따른 실제 종료를 확인했다. 이미 발생한 효과의 rollback을 뜻하지 않는다. */
    data class Cancelled(
        override val taskId: TaskId,
        override val output: TaskOutput? = null,
        override val usage: AgentUsage = AgentUsage.Unknown,
        override val sessionUsage: AgentUsage? = null,
    ) : TaskOutcome

    /**
     * 이 handle의 관찰·제어를 끝내면서 실제 종결 결과를 확인하지 못했다.
     *
     * 외부 작업과 효과는 이후에도 계속될 수 있다. 지원할 수 없는 정상 작업을 받아들이기 위한
     * 우회 수단이 아니며, 확인 가능한 결과를 이 판정으로 낮추는 구현은 부적합하다.
     *
     * @property reason 확인하지 못한 사유
     * @property known 확인한 사실의 요약. 무엇을 알고 무엇을 모르는지 보존한다
     * @property output 그 시점의 관찰이며 실제 실행의 최종값임을 보장하지 않는다
     */
    data class Unresolved(
        override val taskId: TaskId,
        val reason: UnresolvedReason,
        val known: String,
        override val output: TaskOutput? = null,
        override val usage: AgentUsage = AgentUsage.Unknown,
        override val sessionUsage: AgentUsage? = null,
    ) : TaskOutcome
}

/** 종결 결과를 확인하지 못한 사유. 어느 값도 실제 종료를 주장하지 않는다. */
enum class UnresolvedReason {
    /** 관찰 stream이나 전달 경로만 끊겼다. */
    OBSERVATION_LOST,

    /** 공개한 정리 상한 안에서 종결을 확인하지 못했다. */
    CLEANUP_BOUND_EXCEEDED,

    /** 취소 요청은 수락됐으나 실제 종료를 확인하지 못했다. */
    CANCELLATION_UNCONFIRMED,

    /** 일부 종료 사실은 알지만 Task 전체의 결과를 판정할 수 없다. */
    PARTIAL_EVIDENCE,
}

/** 확인한 실행 종료가 어떤 이유로 끝났는가. */
enum class StopReason {
    /** agent가 스스로 작업을 끝냈다. */
    FINISHED,
    /** 실행 반복 한도에 도달해 멈췄다. 내부 turn·step을 공통 작업 단위로 요구하지 않는다. */
    ITERATION_LIMIT,
    /** provider가 반복 루프를 감지해 멈췄다. */
    LOOP_DETECTED,
    /** provider가 그 밖의 이유로 멈췄으나 실패로 보고하지 않았다. */
    PROVIDER_STOPPED,
}

/** 확인한 실패의 분류. */
enum class FailureKind {
    /** 재시도가 성공할 수 있다: rate limit, overload, 연결 오류, provider 5xx. */
    TRANSIENT,
    AUTHENTICATION,
    /** provider 정책·sandbox·안전 필터가 막았다. */
    POLICY_BLOCKED,
    CONTEXT_OVERFLOW,
    /** session·계정 예산이 소진됐다. */
    BUDGET_EXCEEDED,
    /** provider가 그 밖의 실패를 보고했다. */
    PROVIDER,
    /** 실행 수단·transport 실패가 Task 실패로 이어졌음이 확인됐다. 관찰 상실만이면 Unresolved다. */
    TRANSPORT,
    UNKNOWN,
}

/**
 * 호출자에게 전달한 업무 산출물.
 *
 * @property complete `false`면 확정 시점까지 확보한 부분 산출물이다. 부분 산출물의 존재가
 * 완료나 업무 성공의 근거는 아니다
 */
sealed interface TaskOutput {
    val complete: Boolean

    data class Text(
        val text: String,
        override val complete: Boolean = true,
    ) : TaskOutput

    /**
     * @property json provider가 전달한 구조화 산출물의 원문
     * @property validation 요구한 schema에 대한 검증 결과. 검증하지 않았다면
     * [SchemaValidation.NOT_VALIDATED]이며 성공으로 합성하지 않는다
     */
    data class Structured(
        val json: String,
        val validation: SchemaValidation,
        override val complete: Boolean = true,
    ) : TaskOutput
}

enum class SchemaValidation { VALID, INVALID, NOT_VALIDATED }

/**
 * 누적 사용량 snapshot. `null`은 provider가 신뢰할 값을 제공하지 않았다는 뜻이고 `0`은 실제로
 * 보고된 0이다. [totalTokens]가 다른 필드의 합이라고 가정하지 않는다.
 */
data class AgentUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    /**
     * 서로 겹치지 않는 실행 구간의 필드별 합. 어느 쪽이든 unknown이면 합도 unknown이다.
     * 누적 snapshot을 갱신하는 연산이 아니다. 합산의 시작점이 실제 0임을 알 때만 [Zero]를 쓴다.
     */
    operator fun plus(other: AgentUsage): AgentUsage = AgentUsage(
        add(inputTokens, other.inputTokens),
        add(cachedInputTokens, other.cachedInputTokens),
        add(outputTokens, other.outputTokens),
        add(reasoningTokens, other.reasoningTokens),
        add(totalTokens, other.totalTokens),
    )

    /** 필드별 차. 어느 쪽이든 모르거나 누적값이 감소해 baseline을 신뢰할 수 없으면 `null`이다. */
    operator fun minus(other: AgentUsage): AgentUsage = AgentUsage(
        sub(inputTokens, other.inputTokens),
        sub(cachedInputTokens, other.cachedInputTokens),
        sub(outputTokens, other.outputTokens),
        sub(reasoningTokens, other.reasoningTokens),
        sub(totalTokens, other.totalTokens),
    )

    private fun add(a: Long?, b: Long?): Long? = if (a == null || b == null) null else Math.addExact(a, b)
    private fun sub(a: Long?, b: Long?): Long? = if (a == null || b == null || a < b) null else a - b

    companion object {
        /** 알려진 값이 없는 snapshot. */
        val Unknown = AgentUsage()

        /** 모든 필드가 실제 0임이 알려진 baseline. 측정하지 않았다는 뜻이 아니다. */
        val Zero = AgentUsage(0, 0, 0, 0, 0)
    }
}
