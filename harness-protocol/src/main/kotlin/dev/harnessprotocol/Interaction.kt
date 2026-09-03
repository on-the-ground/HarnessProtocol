package dev.harnessprotocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Non-blank identifier of one caller interaction (an approval request) within
 * an execution. Scoped to the [ExecutionId]; opaque to consumers.
 */
@JvmInline
value class InteractionId(val value: String) {
    init {
        require(value.isNotBlank()) { "interaction id must not be blank" }
    }
}

/**
 * A provider request that pauses the agent loop until the caller answers
 * through [AgentExecution.respond] or the provider clears it.
 *
 * Only approvals exist in 0.1.0; the pinned SDKs expose no user-question
 * request. The interface is sealed so a later kind can be added without
 * changing [AgentEvent.InteractionRequested].
 */
sealed interface InteractionRequest {
    val interactionId: InteractionId

    /** Work item the request concerns, when the provider identifies one. */
    val workId: WorkId?

    /** Provider payload preserved for display and diagnostics. */
    val detail: JsonElement

    /**
     * Approval of an external effect the provider will not perform on its own
     * under [ApprovalPolicy.CALLER_DECIDES].
     *
     * @property prompt provider description of the effect (command line, change reason)
     * @property effect portable effect category when known
     * @property availableDecisions decisions this particular request accepts;
     * [AgentExecution.respond] rejects any other decision
     */
    data class Approval(
        override val interactionId: InteractionId,
        override val workId: WorkId?,
        val prompt: String,
        val effect: EffectKind?,
        val availableDecisions: Set<ApprovalDecision>,
        override val detail: JsonElement = JsonNull,
    ) : InteractionRequest
}

/** Caller decision for an [InteractionRequest.Approval]. */
enum class ApprovalDecision {
    /** Allow this effect once. */
    APPROVE_ONCE,

    /** Allow this effect for the rest of the session; offered only when the provider supports it. */
    APPROVE_FOR_SESSION,

    /** Refuse this effect; the agent loop continues and may choose another action. */
    DECLINE,

    /** Refuse this effect and ask the provider to stop the current agent loop. */
    CANCEL,
}

/** Caller answer to an [InteractionRequest]. */
sealed interface InteractionResponse {
    data class Approval(val decision: ApprovalDecision) : InteractionResponse
}

/** How an [InteractionRequest] was closed. */
sealed interface InteractionResolution {
    /** The caller answered through [AgentExecution.respond]. */
    data class Responded(val response: InteractionResponse) : InteractionResolution

    /** The provider or adapter closed the request without a caller answer. */
    data class Cleared(val reason: ClearReason) : InteractionResolution
}

/** Why a request was cleared without a caller answer. */
enum class ClearReason {
    /** The turn completed before the caller answered. */
    TURN_COMPLETED,

    /** The execution was cancelled or the session released while the request was open. */
    TURN_INTERRUPTED,

    /** A newer request replaced this one. */
    SUPERSEDED,

    /** The provider withdrew the request for a reason it did not classify. */
    PROVIDER,
}
