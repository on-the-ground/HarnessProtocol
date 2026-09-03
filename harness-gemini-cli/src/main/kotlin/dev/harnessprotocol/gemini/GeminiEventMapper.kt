package dev.harnessprotocol.gemini

import dev.harnessprotocol.AgentEvent
import dev.harnessprotocol.AgentResult
import dev.harnessprotocol.AgentUsage
import dev.harnessprotocol.EffectKind
import dev.harnessprotocol.ExecutionId
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.MessagePhase
import dev.harnessprotocol.ProviderId
import dev.harnessprotocol.StopReason
import dev.harnessprotocol.WarningKind
import dev.harnessprotocol.WorkId
import dev.harnessprotocol.WorkStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Translates raw Gemini CLI SDK stream events (as forwarded by the Node host)
 * into [AgentEvent]s for one execution.
 *
 * Usage: every `finished` event carries the usage of one model call, so the
 * execution's usage is the field-wise sum across them. The SDK reports no
 * session-level usage.
 */
internal class GeminiEventMapper(
    private val executionId: ExecutionId,
) {
    private val finalMessage = StringBuilder()
    private var usage: AgentUsage? = null
    private var stopReason = StopReason.FINISHED
    private var contextPressure = false

    fun map(raw: JsonObject): List<AgentEvent> {
        val type = raw.stringOrNull("type") ?: "unknown"
        val value = raw["value"] ?: JsonNull
        val semantic = when (type) {
            "execution_started" -> listOf(AgentEvent.ExecutionStarted(executionId))
            "content" -> {
                val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                finalMessage.append(text)
                listOf(AgentEvent.MessageDelta(executionId, text, MessagePhase.PROGRESS))
            }

            "thought" -> listOf(
                AgentEvent.ReasoningDelta(executionId, value.readableText()),
            )

            "tool_call_request" -> mapToolEvents(value, WorkStatus.STARTED)
            "tool_call_response" -> mapToolEvents(value, value.workStatus())
            "chat_compressed" -> listOf(mapCompression(value))
            "finished" -> mapUsage(value)
            "user_cancelled", "execution_cancelled" -> listOf(
                AgentEvent.ExecutionCancelled(executionId),
            )

            "agent_execution_blocked" -> listOf(
                AgentEvent.ExecutionFailed(executionId, FailureKind.POLICY_BLOCKED, value.errorMessage()),
            )

            "invalid_stream" -> listOf(
                AgentEvent.ExecutionFailed(executionId, FailureKind.PROVIDER, value.errorMessage()),
            )

            "error", "execution_failed" -> listOf(
                AgentEvent.ExecutionFailed(executionId, value.failureKind(), value.errorMessage()),
            )

            // The provider stopped the loop without failing: remember why and let the
            // host's execution_completed carry it as the stop reason (A4).
            "max_session_turns" -> { stopReason = StopReason.TURN_LIMIT; emptyList() }
            "loop_detected" -> { stopReason = StopReason.LOOP_DETECTED; emptyList() }
            "agent_execution_stopped" -> { stopReason = StopReason.PROVIDER_STOPPED; emptyList() }

            "context_window_will_overflow" -> {
                contextPressure = true
                listOf(AgentEvent.Warning(executionId, WarningKind.CONTEXT_PRESSURE, value.errorMessage(type)))
            }

            "execution_completed" -> {
                val message = finalMessage.toString()
                listOf(
                    AgentEvent.MessageCompleted(executionId, message, MessagePhase.FINAL),
                    AgentEvent.ExecutionCompleted(executionId, AgentResult(message, stopReason, usage)),
                )
            }

            else -> emptyList()
        }

        return semantic + AgentEvent.ProviderEventObserved(
            executionId = executionId,
            provider = GEMINI,
            name = type,
            payload = raw,
        )
    }

    private fun mapTool(value: JsonElement, fallback: WorkStatus): AgentEvent.ToolCallChanged {
        val tool = value as? JsonObject ?: JsonObject(emptyMap())
        val response = tool["response"] as? JsonObject
        return AgentEvent.ToolCallChanged(
            executionId = executionId,
            workId = WorkId(tool.stringOrNull("callId") ?: tool.stringOrNull("id") ?: "tool"),
            name = tool.stringOrNull("name") ?: "unknown",
            status = tool.statusOr(fallback),
            arguments = tool["args"] ?: JsonNull,
            result = response?.get("responseParts") ?: tool["result"] ?: JsonNull,
            error = tool["error"]?.errorMessage(),
        )
    }

    private fun mapToolEvents(value: JsonElement, fallback: WorkStatus): List<AgentEvent> {
        val toolEvent = mapTool(value, fallback)
        val effectKind = toolEvent.name.effectKind() ?: return listOf(toolEvent)
        val tool = value as? JsonObject ?: JsonObject(emptyMap())
        val args = tool["args"] as? JsonObject
        val changedPath = args?.stringOrNull("file_path")
            ?: args?.stringOrNull("path")
            ?: args?.stringOrNull("absolute_path")
        return listOf(
            toolEvent,
            AgentEvent.EffectChanged(
                executionId = executionId,
                workId = toolEvent.workId,
                kind = effectKind,
                status = toolEvent.status,
                description = when (effectKind) {
                    EffectKind.COMMAND -> args?.stringOrNull("command")
                    EffectKind.WEB_SEARCH -> args?.stringOrNull("query")
                    else -> toolEvent.name
                },
                output = if (fallback == WorkStatus.STARTED) null else toolEvent.result.readableText(),
                changedPaths = changedPath?.let(::listOf).orEmpty(),
            ),
        )
    }

    private fun mapCompression(value: JsonElement): AgentEvent.ContextManaged {
        val info = value as? JsonObject
        return AgentEvent.ContextManaged(
            executionId = executionId,
            beforeTokens = info?.longOrNull("originalTokenCount"),
            afterTokens = info?.longOrNull("newTokenCount"),
        )
    }

    private fun mapUsage(value: JsonElement): List<AgentEvent> {
        val finished = value as? JsonObject ?: return emptyList()
        val metadata = finished["usageMetadata"] as? JsonObject ?: return emptyList()
        val call = AgentUsage(
            inputTokens = metadata.longOrNull("promptTokenCount"),
            cachedInputTokens = metadata.longOrNull("cachedContentTokenCount"),
            outputTokens = metadata.longOrNull("candidatesTokenCount"),
            reasoningTokens = metadata.longOrNull("thoughtsTokenCount"),
            totalTokens = metadata.longOrNull("totalTokenCount"),
        )
        val accumulated = usage?.plus(call) ?: call
        usage = accumulated
        return listOf(AgentEvent.UsageChanged(executionId, accumulated, session = null))
    }

    /** Gemini errors carry no structured code; classify only what the stream itself reveals. */
    private fun JsonElement.failureKind(): FailureKind {
        if (contextPressure) return FailureKind.CONTEXT_OVERFLOW
        val text = errorMessage().lowercase()
        return when {
            "context window" in text || "token limit" in text -> FailureKind.CONTEXT_OVERFLOW
            else -> FailureKind.PROVIDER
        }
    }
}

private val GEMINI = ProviderId("gemini-cli")

private fun String.effectKind(): EffectKind? = when (lowercase()) {
    "run_shell_command", "shell", "execute_command" -> EffectKind.COMMAND
    "write_file", "replace", "apply_patch", "edit_file", "delete_file" -> EffectKind.FILE_CHANGE
    "google_web_search", "web_search", "web_fetch" -> EffectKind.WEB_SEARCH
    else -> null
}

private fun JsonElement.readableText(): String {
    if (this is JsonPrimitive) return contentOrNull.orEmpty()
    if (this is JsonObject) {
        return stringOrNull("description")
            ?: stringOrNull("subject")
            ?: stringOrNull("text")
            ?: toString()
    }
    return toString()
}

private fun JsonElement.errorMessage(fallback: String = "Gemini CLI execution failed"): String {
    if (this is JsonPrimitive) return contentOrNull ?: fallback
    if (this is JsonObject) {
        val nested = this["error"]
        return stringOrNull("message")
            ?: nested?.errorMessage(fallback)
            ?: stringOrNull("reason")
            ?: stringOrNull("systemMessage")
            ?: fallback
    }
    return fallback
}

private fun JsonElement.workStatus(): WorkStatus =
    (this as? JsonObject)?.statusOr(WorkStatus.COMPLETED) ?: WorkStatus.COMPLETED

private fun JsonObject.statusOr(fallback: WorkStatus): WorkStatus = when (stringOrNull("status")) {
    "scheduled", "validating", "awaiting_approval", "executing", "in_progress" -> WorkStatus.STARTED
    "success", "completed" -> WorkStatus.COMPLETED
    "error", "failed" -> WorkStatus.FAILED
    "cancelled" -> WorkStatus.CANCELLED
    "declined" -> WorkStatus.DECLINED
    else -> fallback
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull
