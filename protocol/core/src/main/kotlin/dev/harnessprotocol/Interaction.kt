package dev.harnessprotocol

/**
 * 작업을 진행하기 위해 외부 판단·정보를 요청한다.
 *
 * 승인과 질문은 전달·대기·일회 응답·정리라는 같은 lifecycle을 쓰고 응답의 의미만 다르다.
 * vendor 원본 [detail]을 해석해야만 응답할 수 있는 구조를 필수 경로로 만들지 않는다.
 */
sealed interface InteractionRequest {
    val interactionId: InteractionId

    /** 요청이 가리키는 하위 작업. provider가 식별하지 않으면 `null`이다. */
    val workId: WorkId?

    /** 표시와 진단을 위한 provider 원본. 응답에 필요한 정보는 공통 필드로 제공된다. */
    val detail: String?

    /**
     * 행위의 허용·거절 판단을 요청한다. [ApprovalRequirement.CallerDecides]에서만 발생한다.
     *
     * @property prompt 승인 대상 행위의 설명. 명령줄이나 변경 사유
     * @property effect 알 수 있으면 portable 효과 분류
     * @property availableDecisions 이 요청이 실제로 받는 결정. 그 밖의 결정은 전달 전에 거절된다
     * @property sessionGrant 세션 승인이 반복 허용할 대상·조건. 범위를 설명·집행할 수 없으면
     * APPROVE_FOR_SESSION 선택지를 제공하지 않는다
     */
    data class Approval(
        override val interactionId: InteractionId,
        override val workId: WorkId?,
        val prompt: String,
        val effect: EffectKind?,
        val availableDecisions: Set<ApprovalDecision>,
        val sessionGrant: SessionApprovalGrant? = null,
        override val detail: String? = null,
    ) : InteractionRequest {
        init {
            require(availableDecisions.isNotEmpty()) { "approval must offer at least one decision" }
            require((ApprovalDecision.APPROVE_FOR_SESSION in availableDecisions) == (sessionGrant != null)) {
                "session approval requires an explicit grant, and only that decision may carry one"
            }
        }
    }

    /**
     * 진행에 필요한 정보를 묻는다. 최초 입력이나 새 작업과 구별한다.
     *
     * @property prompt 호출자에게 보여줄 질문
     * @property choices 제시된 선택지. 비어 있으면 자유 응답이다
     * @property allowsFreeForm [choices]가 있어도 자유 응답을 받는지
     */
    data class Question(
        override val interactionId: InteractionId,
        override val workId: WorkId?,
        val prompt: String,
        val choices: List<String> = emptyList(),
        val allowsFreeForm: Boolean = choices.isEmpty(),
        override val detail: String? = null,
    ) : InteractionRequest
}

/**
 * 세션 승인으로 반복 허용할 행위의 범위.
 *
 * @property scopeId 현재 논리 session 안의 허용 범위 identity. 대상·조건이 같을 때만 재사용한다.
 * 다른 session의 같은 문자열은 같은 권한이 아니다
 * @property description 허용할 대상과 조건을 caller가 판단할 수 있는 설명. 예: 지정한 보고서
 * 디렉터리 안의 파일 쓰기. provider 원본 payload를 해석해야 범위를 알 수 있게 만들지 않는다
 *
 * 적용 기간은 현재 논리 session이 끝나거나 grant가 철회될 때까지다. 범위 밖의 행위에는 별도
 * 승인이 필요하고, 동일 scopeId를 더 넓은 권한으로 재해석하지 않는다. 영속 문맥 재개만으로
 * 이 grant의 보관을 가정하지 않는다. Adapter가 범위를 확정·집행할 수 없으면 이 선택지를 생략한다.
 */
data class SessionApprovalGrant(
    val scopeId: ApprovalScopeId,
    val description: String,
) {
    init { require(description.isNotBlank()) { "session grant must describe its targets and conditions" } }
}

/** 승인 요청에 대한 결정. 요청이 제시한 값만 사용할 수 있다. */
enum class ApprovalDecision {
    /** 이 행위를 한 번 허용한다. */
    APPROVE_ONCE,
    /** 명시된 [SessionApprovalGrant] 범위만 현재 논리 session의 후속 작업에 반복 허용한다. */
    APPROVE_FOR_SESSION,
    /** 이 행위만 거절한다. 작업은 계속되며 다른 행동을 선택할 수 있다. */
    DECLINE,
    /** 이 행위를 거절하고 provider에 작업 중단을 요청한다. */
    CANCEL,
}

/** 요청에 대한 caller의 응답. 요청 종류와 맞아야 한다. */
sealed interface InteractionResponse {
    data class Approval(val decision: ApprovalDecision) : InteractionResponse

    /**
     * @property text 자유 응답 또는 선택한 값. 요청이 허용한 형태여야 한다
     */
    data class Answer(val text: String) : InteractionResponse
}

/** 요청이 어떻게 닫혔는가. */
sealed interface InteractionResolution {
    /** caller가 응답했다. */
    data class Responded(val response: InteractionResponse) : InteractionResolution

    /** 응답 없이 정리됐다. [reason]은 관찰한 실제 원인이다. */
    data class Cleared(val reason: ClearReason) : InteractionResolution
}

/**
 * 응답 없이 정리된 실제 원인.
 *
 * [CANCELLATION_REQUESTED]는 실제 취소 완료와 다르고, [TASK_ENDED]는 handle의 종결에 따른
 * 정리이며 미확정 작업의 물리적 종료를 주장하지 않는다.
 */
enum class ClearReason {
    CANCELLATION_REQUESTED,
    TASK_ENDED,
    SUPERSEDED,
    PROVIDER_WITHDRAWN,
    /** 응답 수락 여부가 미확정이어서 재응답 대상에서 제외됐다. provider가 거절했다는 뜻은 아니다. */
    RESPONSE_UNCONFIRMED,
}
