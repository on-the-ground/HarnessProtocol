package dev.harnessprotocol.codex

import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.serialization.json.*
import java.util.UUID

/** App Server notifications are evidence for the task assigned to this mapper only. */
internal class CodexTaskMapper(private val task: ManagedTask, private val spec: SessionSpec) {
    private var baseline: AgentUsage? = null
    private val partial = mutableMapOf<String, StringBuilder>()
    private var hasFinalOutput = false
    fun accept(raw: JsonObject) {
        val method = raw.cString("method") ?: return
        val payload = raw["payload"] as? JsonObject ?: JsonObject(emptyMap())
        if (spec.requirements.diagnostics == DiagnosticsRequirement.Required)
            task.diagnostic(ProviderDiagnostic(task.id, ProviderId("codex"), method, payload.toString()))
        when (method) {
            "turn/started" -> task.event(TaskEvent.TaskStarted(task.id))
            "item/agentMessage/delta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
                val text = payload.cString("delta") ?: return
                val key = payload.cString("itemId") ?: UUID.randomUUID().toString()
                val role = if (method.contains("reasoning")) MessageRole.EXPLANATION else MessageRole.UNKNOWN
                task.event(TaskEvent.MessageDelta(task.id, MessageId(key), text, role))
                if (role != MessageRole.EXPLANATION && !hasFinalOutput) {
                    val current = partial.getOrPut(key) { StringBuilder() }.append(text)
                    task.capture(TaskOutput.Text(current.toString(), false))
                }
            }
            "item/started", "item/completed" -> item(payload, method == "item/completed")
            "item/commandExecution/outputDelta" -> task.event(TaskEvent.EffectChanged(task.id,
                WorkId(payload.cString("itemId") ?: UUID.randomUUID().toString()), EffectKind.COMMAND, WorkStatus.UPDATED, output = payload.cString("delta")))
            "thread/tokenUsage/updated" -> {
                val usage = payload["tokenUsage"] as? JsonObject ?: payload["token_usage"] as? JsonObject ?: return
                val total = (usage["total"] as? JsonObject)?.cUsage() ?: return
                val last = (usage["last"] as? JsonObject)?.cUsage() ?: AgentUsage.Unknown
                val base = baseline ?: (total - last).also { baseline = it }
                task.event(TaskEvent.UsageChanged(task.id, total - base, total))
            }
            "turn/completed" -> {
                val turn = payload["turn"] as? JsonObject ?: payload
                when (turn.cString("status")) {
                    "completed" -> task.completed()
                    "interrupted", "cancelled" -> task.cancelled()
                    "failed" -> {
                        val error = turn["error"] as? JsonObject ?: JsonObject(emptyMap())
                        task.failed(error.cFailureKind(), error.cString("message") ?: "Codex task failed")
                    }
                    else -> task.unresolved(UnresolvedReason.PARTIAL_EVIDENCE, "App Server reported an unrecognized turn termination status")
                }
            }
            "error" -> {
                val error = payload["error"] as? JsonObject ?: payload
                if (payload["willRetry"]?.jsonPrimitive?.booleanOrNull == true)
                    task.event(TaskEvent.Warning(task.id, WarningKind.RECOVERABLE, error.cString("message") ?: "Provider is retrying"))
                else task.failed(error.cFailureKind(), error.cString("message") ?: "Codex task failed")
            }
            "observation_lost" -> task.unresolved(UnresolvedReason.OBSERVATION_LOST, payload.cString("message") ?: "Codex notification channel was lost")
            "interaction_requested" -> {
                if (spec.requirements.approval != ApprovalRequirement.CallerDecides) return
                val id = payload.cString("interactionId") ?: return
                // The current native payload does not describe an enforceable session grant.
                // Keep one-shot choices; never turn an opaque acceptForSession into a blanket grant.
                val decisions = (payload["availableDecisions"] as? JsonArray)?.mapNotNull { cDecision(it.jsonPrimitive.content) }?.toSet()
                    ?: setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE)
                if (decisions.isEmpty()) return
                task.event(TaskEvent.InteractionRequested(task.id, InteractionRequest.Approval(InteractionId(id),
                    payload.cString("workId")?.let(::WorkId), payload.cString("prompt") ?: "Approve this operation?",
                    when (payload.cString("effect")) { "command" -> EffectKind.COMMAND; "file_change" -> EffectKind.FILE_CHANGE; else -> null },
                    decisions, detail = payload["detail"]?.toString())))
            }
            "interaction_resolved" -> {
                val id = payload.cString("interactionId") ?: return
                val resolution = payload["resolution"] as? JsonObject ?: return
                val mapped = if (resolution.cString("type") == "responded") {
                    val decision = cDecision(resolution.cString("decision")) ?: return
                    InteractionResolution.Responded(InteractionResponse.Approval(decision))
                } else InteractionResolution.Cleared(when (resolution.cString("reason")) {
                    "turn_completed" -> ClearReason.TASK_ENDED
                    "turn_interrupted" -> ClearReason.CANCELLATION_REQUESTED
                    "superseded" -> ClearReason.SUPERSEDED
                    else -> ClearReason.PROVIDER_WITHDRAWN
                })
                task.event(TaskEvent.InteractionResolved(task.id, InteractionId(id), mapped))
            }
            "warning", "configWarning" -> task.event(TaskEvent.Warning(task.id,
                if (method == "configWarning" || payload.cString("kind") == "configuration") WarningKind.CONFIGURATION else WarningKind.OTHER,
                payload.cString("message") ?: payload.cString("summary") ?: payload.toString()))
        }
    }
    private fun item(payload: JsonObject, completed: Boolean) {
        val item = payload["item"] as? JsonObject ?: return
        val key = item.cString("id") ?: UUID.randomUUID().toString()
        val status = when (item.cString("status")) {
            "failed", "error" -> WorkStatus.FAILED
            "declined" -> WorkStatus.DECLINED
            "cancelled", "interrupted" -> WorkStatus.CANCELLED
            else -> if (completed) WorkStatus.COMPLETED else WorkStatus.STARTED
        }
        when (item.cString("type")) {
            "agentMessage", "agent_message" -> if (completed) {
                val text = item.cString("text") ?: return
                val role = when (item.cString("phase")) { "final_answer" -> MessageRole.ANSWER; "commentary" -> MessageRole.COMMENTARY; else -> MessageRole.UNKNOWN }
                task.event(TaskEvent.MessageCompleted(task.id, MessageId(key), text, role))
                if (role == MessageRole.ANSWER) { hasFinalOutput = true; task.capture(TaskOutput.Text(text)) }
            }
            "commandExecution", "command_execution" -> task.event(TaskEvent.EffectChanged(task.id, WorkId(key), EffectKind.COMMAND, status,
                description = item.cString("command"), output = item.cString("aggregatedOutput") ?: item.cString("aggregated_output"),
                exitCode = (item["exitCode"] as? JsonPrimitive)?.intOrNull))
            "fileChange", "file_change" -> task.event(TaskEvent.EffectChanged(task.id, WorkId(key), EffectKind.FILE_CHANGE, status,
                changedPaths = (item["changes"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.cString("path") }.orEmpty()))
            "webSearch", "web_search" -> task.event(TaskEvent.EffectChanged(task.id, WorkId(key), EffectKind.WEB_SEARCH, status, description = item.cString("query")))
            "mcpToolCall", "mcp_tool_call", "dynamicToolCall", "dynamic_tool_call" -> task.event(TaskEvent.ToolCallChanged(task.id, WorkId(key),
                item.cString("tool") ?: item.cString("name") ?: "unknown", status, item["arguments"]?.toString(),
                (item["result"] ?: item["contentItems"])?.toString(), (item["error"] as? JsonObject)?.cString("message")))
        }
    }
}
private fun cDecision(value: String?): ApprovalDecision? = when (value) {
    "accept", "approve_once" -> ApprovalDecision.APPROVE_ONCE
    "decline" -> ApprovalDecision.DECLINE
    "cancel" -> ApprovalDecision.CANCEL
    else -> null
}
private fun JsonObject.cString(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.cUsage() = AgentUsage(
    (this["inputTokens"] as? JsonPrimitive)?.longOrNull,
    (this["cachedInputTokens"] as? JsonPrimitive)?.longOrNull,
    (this["outputTokens"] as? JsonPrimitive)?.longOrNull,
    (this["reasoningOutputTokens"] as? JsonPrimitive)?.longOrNull,
    (this["totalTokens"] as? JsonPrimitive)?.longOrNull,
)
private fun JsonObject.cFailureKind(): FailureKind {
    val info = this["codexErrorInfo"] ?: this["codex_error_info"]
    val code = (info as? JsonPrimitive)?.contentOrNull ?: (info as? JsonObject)?.keys?.singleOrNull()
    val status = ((info as? JsonObject)?.values?.firstOrNull() as? JsonObject)?.get("httpStatusCode")?.jsonPrimitive?.intOrNull
        ?: (this["httpStatusCode"] as? JsonPrimitive)?.intOrNull
    val byStatus = when (status) {
        401, 403 -> FailureKind.AUTHENTICATION
        408, 409, 425, 429, 500, 502, 503, 504 -> FailureKind.TRANSIENT
        else -> null
    }
    return when (code) {
        "contextWindowExceeded" -> FailureKind.CONTEXT_OVERFLOW
        "sessionBudgetExceeded", "usageLimitExceeded" -> FailureKind.BUDGET_EXCEEDED
        "serverOverloaded", "internalServerError" -> FailureKind.TRANSIENT
        "httpConnectionFailed", "responseStreamDisconnected", "responseStreamConnectionFailed", "responseTooManyFailedAttempts" -> byStatus ?: FailureKind.TRANSIENT
        "unauthorized" -> FailureKind.AUTHENTICATION
        "cyberPolicy", "sandboxError" -> FailureKind.POLICY_BLOCKED
        "badRequest", "threadRollbackFailed", "activeTurnNotSteerable", "other" -> FailureKind.PROVIDER
        null -> byStatus ?: FailureKind.UNKNOWN
        else -> FailureKind.UNKNOWN
    }
}
