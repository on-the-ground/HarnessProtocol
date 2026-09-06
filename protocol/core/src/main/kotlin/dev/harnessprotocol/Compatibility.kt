package dev.harnessprotocol

/**
 * 요구한 의미를 보존할 수 있는지에 대한 진단.
 *
 * 확정적인 미지원과 판단에 필요한 사실을 아직 확인하지 못한 경우를 구별한다.
 * [CompatibilityIssueKind.ADVISORY]는 필수 요구를 그대로 지킬 수 있을 때만 사용한다.
 */
data class CompatibilityReport(
    val issues: List<CompatibilityIssue> = emptyList(),
) {
    val status: CompatibilityStatus
        get() = when {
            issues.any { it.kind == CompatibilityIssueKind.UNSUPPORTED } -> CompatibilityStatus.INCOMPATIBLE
            issues.any { it.kind == CompatibilityIssueKind.UNCONFIRMED } -> CompatibilityStatus.UNCONFIRMED
            else -> CompatibilityStatus.COMPATIBLE
        }

    val isCompatible: Boolean get() = status == CompatibilityStatus.COMPATIBLE

    /** 미확인은 호환 성공으로 취급하지 않으며 확정적인 미지원과 다른 예외로 전달한다. */
    fun requireCompatible() {
        when (status) {
            CompatibilityStatus.COMPATIBLE -> Unit
            CompatibilityStatus.INCOMPATIBLE -> throw IncompatibleRequirementException(issues)
            CompatibilityStatus.UNCONFIRMED -> throw RequirementUnconfirmedException(issues)
        }
    }

    companion object {
        val Compatible = CompatibilityReport()
    }
}

/**
 * @property path 요구 안의 위치. 예: `requirements.persistence`
 * @property message 왜 보존할 수 없는지에 대한 설명
 */
data class CompatibilityIssue(
    val path: String,
    val message: String,
    val kind: CompatibilityIssueKind = CompatibilityIssueKind.UNSUPPORTED,
)

enum class CompatibilityStatus { COMPATIBLE, INCOMPATIBLE, UNCONFIRMED }

enum class CompatibilityIssueKind {
    /** 필수 의미는 지켜지며 부가 정보만 전달한다. */
    ADVISORY,
    /** 해당 요청의 필수 의미를 이행할 수 없음이 확인됐다. */
    UNSUPPORTED,
    /** 조건·환경을 확인하지 못해 이행 가능 여부를 판정하지 못했다. */
    UNCONFIRMED,
}

/** 요구 이행 가능 여부가 미확정이다. 작업 수락·실행 여부의 미확정과는 다른 진단이다. */
class RequirementUnconfirmedException(
    val issues: List<CompatibilityIssue>,
) : RuntimeException(
    issues.joinToString(prefix = "요구 이행 여부를 확인하지 못했다: ", separator = "; ") { "${it.path}: ${it.message}" },
)

/** adapter가 요구한 의미를 보존할 수 없다. session 생성·작업 시작 전에 던진다. */
class IncompatibleRequirementException(
    val issues: List<CompatibilityIssue>,
) : RuntimeException(
    issues.joinToString(prefix = "요구를 보존할 수 없다: ", separator = "; ") { "${it.path}: ${it.message}" },
)

/** 실행 수단·transport 경계의 실패. 그 자체가 외부 작업의 실패는 아니다. */
class HarnessTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 작업 시작 요청의 수락 여부를 확인하지 못했다.
 *
 * handle을 받지 못했다는 사실이 작업 미실행의 증거가 아니다. 같은 문맥의 다음 작업은
 * 차단되며 이 요청을 새 작업으로 자동 재전송하지 않는다.
 *
 * @property reference 이후 확인·복구에 사용할 요청 identity
 */
class TaskStartUnconfirmedException(
    val reference: UnconfirmedStart,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 응답을 전송한 뒤 수락 확인을 잃었다. 수락 또는 미수락 어느 쪽도 확정하지 않는다.
 *
 * 이 요청은 재응답 대상에서 제외되며 원래 Task에서 같은 응답을 자동 재전송하지 않는다.
 * caller는 계속 관찰하거나 취소·정리를 요청할 수 있다. 별도 확인·복구 연산을 암묵적으로
 * 지원한다는 뜻은 아니다.
 */
class InteractionResponseUnconfirmedException(
    val reference: UnconfirmedResponse,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 소유 harness의 Task와 그 안의 일회 응답 요청을 함께 식별한다. */
data class UnconfirmedResponse(
    val taskId: TaskId,
    val interactionId: InteractionId,
)

/**
 * 수락 여부를 모르는 시작 요청의 identity.
 *
 * @property requestId adapter가 그 요청에 부여한 값. provider가 Task를 만들었다면 이후
 * 조회·복구 계약이 이 값으로 대응시킨다
 */
data class UnconfirmedStart(
    val sessionId: SessionId,
    val requestId: String,
) {
    init { require(requestId.isNotBlank()) { "request id must not be blank" } }
}

/**
 * 문맥의 종결·일관성을 확인하기 전이라 새 작업을 시작할 수 없다.
 *
 * 차단 범위는 소유 harness가 관리하는 같은 논리 session의 모든 handle이며 그 harness의 수명
 * 동안 유지된다. `release`와 독립된 새 session 생성은 여전히 가능하다.
 */
class SessionBlockedException(
    val sessionId: SessionId,
    message: String,
) : IllegalStateException(message)
