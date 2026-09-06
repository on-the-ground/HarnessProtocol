package dev.harnessprotocol.gemini

import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.serialization.json.*
import java.util.UUID

/** Only host-confirmed sendStream termination settles a Gemini task; inner model turns do not. */
internal class GeminiTaskMapper(private val task: ManagedTask, private val spec: SessionSpec) {
    private var usage: AgentUsage = AgentUsage.Zero
    private var reason = StopReason.FINISHED
    private var message = StringBuilder()
    private var messageId = MessageId(UUID.randomUUID().toString())
    private var lastText: String? = null
    fun accept(raw: JsonObject) {
        val type = raw.gString("type") ?: return
        val value = raw["value"] ?: JsonNull
        if (spec.requirements.diagnostics == DiagnosticsRequirement.Required)
            task.diagnostic(ProviderDiagnostic(task.id, ProviderId("gemini-cli"), type, raw.toString()))
        when (type) {
            "execution_started" -> task.event(TaskEvent.TaskStarted(task.id))
            "content" -> {
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return
                message.append(text)
                lastText = message.toString()
                task.capture(TaskOutput.Text(lastText!!, false))
                task.event(TaskEvent.MessageDelta(task.id, messageId, text, MessageRole.UNKNOWN))
            }
            "thought" -> task.event(TaskEvent.MessageDelta(task.id, MessageId("explanation:${messageId.value}"), value.gText(), MessageRole.EXPLANATION))
            "finished" -> {
                if (lastText != null && message.isNotEmpty()) task.event(TaskEvent.MessageCompleted(task.id, messageId, message.toString(), MessageRole.UNKNOWN))
                message = StringBuilder()
                messageId = MessageId(UUID.randomUUID().toString())
                val metadata = (value as? JsonObject)?.get("usageMetadata") as? JsonObject
                val delta = metadata?.let { AgentUsage(it.gLong("promptTokenCount"), it.gLong("cachedContentTokenCount"),
                    it.gLong("candidatesTokenCount"), it.gLong("thoughtsTokenCount"), it.gLong("totalTokenCount")) } ?: AgentUsage.Unknown
                usage += delta
                task.event(TaskEvent.UsageChanged(task.id, usage))
            }
            "tool_call_request", "tool_call_response" -> {
                val tool = value as? JsonObject ?: return
                val key = tool.gString("callId") ?: tool.gString("id") ?: UUID.randomUUID().toString()
                val status = when (tool.gString("status")) {
                    "error", "failed" -> WorkStatus.FAILED
                    "cancelled" -> WorkStatus.CANCELLED
                    "declined" -> WorkStatus.DECLINED
                    else -> if (type == "tool_call_request") WorkStatus.STARTED else WorkStatus.COMPLETED
                }
                task.event(TaskEvent.ToolCallChanged(task.id, WorkId(key), tool.gString("name") ?: "unknown", status,
                    tool["args"]?.toString(), (tool["response"] ?: tool["result"])?.toString(), tool["error"]?.gText()))
                // Effect classification is reserved for the SDK's known built-in tool identities.
                val effect = when (tool.gString("name")) {
                    "run_shell_command" -> EffectKind.COMMAND
                    "write_file", "replace" -> EffectKind.FILE_CHANGE
                    "google_web_search" -> EffectKind.WEB_SEARCH
                    else -> null
                }
                if (effect != null) {
                    val args = tool["args"] as? JsonObject
                    task.event(TaskEvent.EffectChanged(task.id, WorkId(key), effect, status,
                        description = when (effect) {
                            EffectKind.COMMAND -> args?.gString("command")
                            EffectKind.FILE_CHANGE -> args?.gString("file_path") ?: args?.gString("path")
                            EffectKind.WEB_SEARCH -> args?.gString("query")
                            else -> null
                        }))
                }
            }
            "max_session_turns" -> reason = StopReason.ITERATION_LIMIT
            "loop_detected" -> reason = StopReason.LOOP_DETECTED
            "agent_execution_stopped" -> reason = StopReason.PROVIDER_STOPPED
            "context_window_will_overflow" -> task.event(TaskEvent.Warning(task.id, WarningKind.CONTEXT_PRESSURE, value.gText()))
            "execution_completed" -> { lastText?.let { task.capture(TaskOutput.Text(it)) }; task.completed(reason) }
            "execution_cancelled" -> task.cancelled()
            "execution_failed" -> {
                val detail = value as? JsonObject
                val kind = when (detail?.gString("failureKind")) { "policy_blocked" -> FailureKind.POLICY_BLOCKED; else -> FailureKind.UNKNOWN }
                task.failed(kind, value.gText())
            }
            "observation_lost" -> task.unresolved(UnresolvedReason.OBSERVATION_LOST, value.gText())
            // user_cancelled/error/invalid_stream are observations inside sendStream. The host
            // must finish the owned invocation before it reports task termination.
        }
    }
}
private fun JsonObject.gString(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.gLong(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonElement.gText(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonObject -> gString("message") ?: gString("text") ?: gString("description") ?: toString()
    else -> toString()
}
