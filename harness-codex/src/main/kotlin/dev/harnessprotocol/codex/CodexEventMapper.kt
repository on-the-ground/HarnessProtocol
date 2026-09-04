package dev.harnessprotocol.codex

import dev.harnessprotocol.legacy.AgentEvent
import dev.harnessprotocol.legacy.AgentResult
import dev.harnessprotocol.legacy.AgentUsage
import dev.harnessprotocol.legacy.ApprovalDecision
import dev.harnessprotocol.legacy.ClearReason
import dev.harnessprotocol.legacy.InteractionId
import dev.harnessprotocol.legacy.InteractionRequest
import dev.harnessprotocol.legacy.InteractionResolution
import dev.harnessprotocol.legacy.InteractionResponse
import dev.harnessprotocol.legacy.EffectKind
import dev.harnessprotocol.legacy.ExecutionId
import dev.harnessprotocol.legacy.FailureKind
import dev.harnessprotocol.legacy.MessagePhase
import dev.harnessprotocol.legacy.ProviderId
import dev.harnessprotocol.legacy.StopReason
import dev.harnessprotocol.legacy.WarningKind
import dev.harnessprotocol.legacy.WorkId
import dev.harnessprotocol.legacy.WorkStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * Translates raw Codex App Server notifications (as forwarded by the Python host)
 * into [AgentEvent]s for one execution.
 *
 * Usage: `thread/tokenUsage/updated` carries `tokenUsage.total` (thread
 * cumulative) and `tokenUsage.last`. The execution's own usage is derived as
 * `total - baseline` where `baseline = firstTotal - firstLast`, which is correct
 * whether `last` is the last model call or the running turn total.
 */
internal class CodexEventMapper(
    private val executionId: ExecutionId,
) {
    private val progressMessage = StringBuilder()
    private var finalMessage: String? = null
    private var baseline: AgentUsage? = null
    private var executionUsage: AgentUsage? = null
    private var sessionUsage: AgentUsage? = null

    fun map(raw: JsonObject): List<AgentEvent> {
        val method = raw.stringOrNull("method") ?: "unknown"
        val payload = raw["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val semantic = when (method) {
            "turn/started" -> listOf(AgentEvent.ExecutionStarted(executionId))
            "item/agentMessage/delta" -> mapMessageDelta(payload)
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" ->
                payload.stringOrNull("delta")?.let {
                    listOf(AgentEvent.ReasoningDelta(executionId, it))
                }.orEmpty()

            "item/commandExecution/outputDelta" -> listOf(
                AgentEvent.EffectChanged(
                    executionId = executionId,
                    workId = WorkId(payload.stringOrNull("itemId") ?: "command"),
                    kind = EffectKind.COMMAND,
                    status = WorkStatus.UPDATED,
                    output = payload.stringOrNull("delta"),
                ),
            )

            "item/started" -> mapItem(payload, WorkStatus.STARTED)
            "item/completed" -> mapItem(payload, WorkStatus.COMPLETED)
            "thread/tokenUsage/updated" -> mapUsage(payload)
            "turn/completed" -> mapTurnCompleted(payload)
            "error" -> mapError(payload)
            "interaction_requested" -> mapInteractionRequested(payload)
            "interaction_resolved" -> mapInteractionResolved(payload)
            "warning" -> listOf(AgentEvent.Warning(executionId, payload.warningKind(), payload.warningText()))
            "configWarning" -> listOf(AgentEvent.Warning(executionId, WarningKind.CONFIGURATION, payload.warningText()))
            else -> emptyList()
        }

        return semantic + AgentEvent.ProviderEventObserved(
            executionId = executionId,
            provider = CODEX,
            name = method,
            payload = payload,
        )
    }

    private fun mapMessageDelta(payload: JsonObject): List<AgentEvent> {
        val delta = payload.stringOrNull("delta") ?: return emptyList()
        progressMessage.append(delta)
        return listOf(AgentEvent.MessageDelta(executionId, delta, MessagePhase.PROGRESS))
    }

    private fun mapItem(payload: JsonObject, lifecycle: WorkStatus): List<AgentEvent> {
        val item = payload.obj("item") ?: return emptyList()
        val itemId = WorkId(item.stringOrNull("id") ?: "unknown")
        return when (item.stringOrNull("type")) {
            "agentMessage", "agent_message" -> {
                val text = item.stringOrNull("text").orEmpty()
                val phase = item.messagePhase()
                // Only a completed final-answer message is the canonical final message;
                // commentary never overwrites it (#10).
                if (lifecycle == WorkStatus.COMPLETED && phase == MessagePhase.FINAL) {
                    finalMessage = text
                }
                if (lifecycle == WorkStatus.COMPLETED) {
                    listOf(AgentEvent.MessageCompleted(executionId, text, phase))
                } else {
                    emptyList()
                }
            }

            "commandExecution", "command_execution" -> listOf(
                AgentEvent.EffectChanged(
                    executionId = executionId,
                    workId = itemId,
                    kind = EffectKind.COMMAND,
                    status = item.workStatus(lifecycle),
                    description = item.stringOrNull("command"),
                    output = item.stringOrNull("aggregatedOutput")
                        ?: item.stringOrNull("aggregated_output"),
                    exitCode = item.intOrNull("exitCode") ?: item.intOrNull("exit_code"),
                ),
            )

            "fileChange", "file_change" -> listOf(
                AgentEvent.EffectChanged(
                    executionId = executionId,
                    workId = itemId,
                    kind = EffectKind.FILE_CHANGE,
                    status = item.workStatus(lifecycle),
                    changedPaths = item["changes"]?.jsonArray?.mapNotNull { change ->
                        (change as? JsonObject)?.stringOrNull("path")
                    }.orEmpty(),
                ),
            )

            "webSearch", "web_search" -> listOf(
                AgentEvent.EffectChanged(
                    executionId = executionId,
                    workId = itemId,
                    kind = EffectKind.WEB_SEARCH,
                    status = item.workStatus(lifecycle),
                    description = item.stringOrNull("query"),
                ),
            )

            "contextCompaction", "context_compaction" -> if (lifecycle == WorkStatus.COMPLETED) {
                listOf(AgentEvent.ContextManaged(executionId))
            } else {
                emptyList()
            }

            "mcpToolCall", "mcp_tool_call", "dynamicToolCall", "dynamic_tool_call" -> listOf(
                AgentEvent.ToolCallChanged(
                    executionId = executionId,
                    workId = itemId,
                    name = item.stringOrNull("tool") ?: item.stringOrNull("name") ?: "unknown",
                    status = item.workStatus(lifecycle),
                    arguments = item["arguments"] ?: JsonNull,
                    result = item["result"] ?: item["contentItems"] ?: JsonNull,
                    error = item.obj("error")?.stringOrNull("message"),
                ),
            )

            else -> emptyList()
        }
    }

    private fun mapUsage(payload: JsonObject): List<AgentEvent> {
        val tokenUsage = payload.obj("tokenUsage") ?: payload.obj("token_usage") ?: return emptyList()
        val total = tokenUsage.obj("total")?.toUsage() ?: return emptyList()
        val last = tokenUsage.obj("last")?.toUsage() ?: AgentUsage.Unknown
        val base = baseline ?: (total - last).also { baseline = it }
        executionUsage = total - base
        sessionUsage = total
        return listOf(AgentEvent.UsageChanged(executionId, executionUsage!!, sessionUsage))
    }

    private fun mapTurnCompleted(payload: JsonObject): List<AgentEvent> {
        val turn = payload.obj("turn") ?: payload
        val status = turn.stringOrNull("status") ?: "completed"
        return when (status) {
            "interrupted", "cancelled" -> listOf(AgentEvent.ExecutionCancelled(executionId))
            "failed" -> {
                val error = turn.obj("error")
                listOf(
                    AgentEvent.ExecutionFailed(
                        executionId,
                        error?.failureKind() ?: FailureKind.PROVIDER,
                        error?.stringOrNull("message") ?: "Codex execution failed",
                    ),
                )
            }

            else -> listOf(
                AgentEvent.ExecutionCompleted(
                    executionId,
                    AgentResult(
                        finalMessage = finalMessage ?: progressMessage.toString(),
                        stopReason = StopReason.FINISHED,
                        usage = executionUsage,
                        sessionUsage = sessionUsage,
                    ),
                ),
            )
        }
    }

    private fun mapInteractionRequested(payload: JsonObject): List<AgentEvent> {
        val interactionId = payload.stringOrNull("interactionId") ?: return emptyList()
        if (payload.stringOrNull("kind") != "approval") return emptyList()
        val decisions = (payload["availableDecisions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDecision() }
            ?.toSet()
            ?.ifEmpty { null }
            ?: setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE)
        return listOf(
            AgentEvent.InteractionRequested(
                executionId,
                InteractionRequest.Approval(
                    interactionId = InteractionId(interactionId),
                    workId = payload.stringOrNull("workId")?.let(::WorkId),
                    prompt = payload.stringOrNull("prompt").orEmpty(),
                    effect = when (payload.stringOrNull("effect")) {
                        "command" -> EffectKind.COMMAND
                        "file_change" -> EffectKind.FILE_CHANGE
                        "web_search" -> EffectKind.WEB_SEARCH
                        null -> null
                        else -> EffectKind.OTHER
                    },
                    availableDecisions = decisions,
                    detail = payload["detail"] ?: JsonNull,
                ),
            ),
        )
    }

    private fun mapInteractionResolved(payload: JsonObject): List<AgentEvent> {
        val interactionId = payload.stringOrNull("interactionId") ?: return emptyList()
        val resolution = payload.obj("resolution") ?: return emptyList()
        val mapped = when (resolution.stringOrNull("type")) {
            "responded" -> InteractionResolution.Responded(
                InteractionResponse.Approval(resolution.stringOrNull("decision")?.toBridgeDecision() ?: ApprovalDecision.DECLINE),
            )
            else -> InteractionResolution.Cleared(
                when (resolution.stringOrNull("reason")) {
                    "turn_completed" -> ClearReason.TURN_COMPLETED
                    "turn_interrupted" -> ClearReason.TURN_INTERRUPTED
                    "superseded" -> ClearReason.SUPERSEDED
                    else -> ClearReason.PROVIDER
                },
            )
        }
        return listOf(AgentEvent.InteractionResolved(executionId, InteractionId(interactionId), mapped))
    }

    private fun mapError(payload: JsonObject): List<AgentEvent> {
        val error = payload.obj("error") ?: payload
        val message = error.stringOrNull("message") ?: payload.stringOrNull("message") ?: "Codex execution failed"
        // App Server may announce a retry it performs itself; that is not terminal.
        if (payload.booleanOrNull("willRetry") == true) {
            return listOf(AgentEvent.Warning(executionId, WarningKind.RECOVERABLE, message))
        }
        return listOf(AgentEvent.ExecutionFailed(executionId, error.failureKind(), message))
    }
}

private val CODEX = ProviderId("codex")

/** App Server decision vocabulary (as advertised by the host) → port decision. */
private fun String.toDecision(): ApprovalDecision? = when (this) {
    "accept" -> ApprovalDecision.APPROVE_ONCE
    "acceptForSession" -> ApprovalDecision.APPROVE_FOR_SESSION
    "decline" -> ApprovalDecision.DECLINE
    "cancel" -> ApprovalDecision.CANCEL
    else -> null
}

/** Bridge-protocol decision names (what the Kotlin side sends) → port decision. */
private fun String.toBridgeDecision(): ApprovalDecision? = when (this) {
    "approve_once" -> ApprovalDecision.APPROVE_ONCE
    "approve_for_session" -> ApprovalDecision.APPROVE_FOR_SESSION
    "decline" -> ApprovalDecision.DECLINE
    "cancel" -> ApprovalDecision.CANCEL
    else -> null
}

private fun JsonObject.messagePhase(): MessagePhase =
    when (stringOrNull("phase")) {
        "commentary" -> MessagePhase.PROGRESS
        else -> MessagePhase.FINAL
    }

private fun JsonObject.workStatus(fallback: WorkStatus): WorkStatus =
    when (stringOrNull("status")) {
        "inProgress", "in_progress", "pending" -> WorkStatus.STARTED
        "completed", "success" -> WorkStatus.COMPLETED
        "failed", "error" -> WorkStatus.FAILED
        "declined" -> WorkStatus.DECLINED
        "cancelled", "interrupted" -> WorkStatus.CANCELLED
        else -> fallback
    }

private fun JsonObject.warningText(): String =
    stringOrNull("message") ?: stringOrNull("summary") ?: toString()

private fun JsonObject.warningKind(): WarningKind =
    when (stringOrNull("kind")) {
        "configuration" -> WarningKind.CONFIGURATION
        "context_pressure" -> WarningKind.CONTEXT_PRESSURE
        else -> WarningKind.OTHER
    }

/**
 * Maps the App Server's structured `codexErrorInfo` (and `httpStatusCode` when
 * present) to a [FailureKind]. Free-text heuristics are deliberately not used.
 */
internal fun JsonObject.failureKind(): FailureKind {
    val info = this["codexErrorInfo"] ?: this["codex_error_info"]
    // Enum variants arrive as strings; structured variants as a single-key object,
    // e.g. {"httpConnectionFailed": {"httpStatusCode": 503}}.
    val code = when (info) {
        is JsonPrimitive -> info.contentOrNull
        is JsonObject -> info.keys.firstOrNull()
        else -> null
    }
    val status = (info as? JsonObject)?.values?.firstOrNull()?.let { (it as? JsonObject)?.intOrNull("httpStatusCode") }
        ?: intOrNull("httpStatusCode")
    val byStatus = when (status) {
        401, 403 -> FailureKind.AUTHENTICATION
        408, 409, 425, 429, 500, 502, 503, 504 -> FailureKind.TRANSIENT
        else -> null
    }
    return when (code) {
        "contextWindowExceeded" -> FailureKind.CONTEXT_OVERFLOW
        "sessionBudgetExceeded", "usageLimitExceeded" -> FailureKind.BUDGET_EXCEEDED
        "serverOverloaded", "internalServerError" -> FailureKind.TRANSIENT
        "httpConnectionFailed", "responseStreamDisconnected", "responseStreamConnectionFailed",
        "responseTooManyFailedAttempts" -> byStatus ?: FailureKind.TRANSIENT
        "unauthorized" -> FailureKind.AUTHENTICATION
        "cyberPolicy", "sandboxError" -> FailureKind.POLICY_BLOCKED
        "badRequest", "threadRollbackFailed", "activeTurnNotSteerable", "other" -> FailureKind.PROVIDER
        null -> byStatus ?: FailureKind.PROVIDER
        else -> FailureKind.UNKNOWN
    }
}

private fun JsonObject.toUsage(): AgentUsage = AgentUsage(
    inputTokens = longOrNull("inputTokens") ?: longOrNull("input_tokens"),
    cachedInputTokens = longOrNull("cachedInputTokens") ?: longOrNull("cached_input_tokens"),
    outputTokens = longOrNull("outputTokens") ?: longOrNull("output_tokens"),
    reasoningTokens = longOrNull("reasoningOutputTokens") ?: longOrNull("reasoning_output_tokens"),
    totalTokens = longOrNull("totalTokens") ?: longOrNull("total_tokens"),
)

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull
