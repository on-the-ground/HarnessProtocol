package dev.harnessprotocol.gemini

import dev.harnessprotocol.legacy.AgentEvent
import dev.harnessprotocol.legacy.EffectKind
import dev.harnessprotocol.legacy.ExecutionId
import dev.harnessprotocol.legacy.FailureKind
import dev.harnessprotocol.legacy.StopReason
import dev.harnessprotocol.legacy.WarningKind
import dev.harnessprotocol.legacy.WorkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GeminiEventMapperTest {
    private val mapper = GeminiEventMapper(ExecutionId("execution-1"))

    private fun finished(prompt: Long, candidates: Long, total: Long) = buildJsonObject {
        put("type", "finished")
        put("value", buildJsonObject {
            put("usageMetadata", buildJsonObject { put("promptTokenCount", prompt); put("candidatesTokenCount", candidates); put("totalTokenCount", total) })
        })
    }

    @Test
    fun `collects streamed content into final result`() {
        mapper.map(buildJsonObject { put("type", "content"); put("value", "hello ") })
        mapper.map(buildJsonObject { put("type", "content"); put("value", "world") })

        val events = mapper.map(buildJsonObject { put("type", "execution_completed") })

        val completed = assertIs<AgentEvent.ExecutionCompleted>(events[1])
        assertEquals("hello world", completed.result.finalMessage)
        assertEquals(StopReason.FINISHED, completed.result.stopReason)
    }

    @Test
    fun `exposes a built in tool as work and as an external effect`() {
        val events = mapper.map(buildJsonObject {
            put("type", "tool_call_request")
            put("value", buildJsonObject {
                put("callId", "call-1")
                put("name", "run_shell_command")
                put("args", buildJsonObject { put("command", "./gradlew test") })
            })
        })

        val tool = assertIs<AgentEvent.ToolCallChanged>(events[0])
        val effect = assertIs<AgentEvent.EffectChanged>(events[1])
        assertEquals("./gradlew test", tool.arguments.jsonObject["command"]?.jsonPrimitive?.content)
        assertEquals(EffectKind.COMMAND, effect.kind)
        assertEquals("./gradlew test", effect.description)
    }

    @Test
    fun `accumulates usage across multiple finished events`() {
        mapper.map(finished(prompt = 10, candidates = 5, total = 15))
        val second = assertIs<AgentEvent.UsageChanged>(mapper.map(finished(prompt = 20, candidates = 7, total = 27)).first())
        assertEquals(30, second.execution.inputTokens)
        assertEquals(12, second.execution.outputTokens)
        assertEquals(42, second.execution.totalTokens)
        assertNull(second.session)
        val result = assertIs<AgentEvent.ExecutionCompleted>(mapper.map(buildJsonObject { put("type", "execution_completed") })[1]).result
        assertEquals(42, result.usage?.totalTokens)
    }

    @Test
    fun `turn limit completes with a stop reason instead of a warning`() {
        val warnings = mapper.map(buildJsonObject { put("type", "max_session_turns"); put("value", "limit") })
        assertNull(warnings.firstOrNull { it is AgentEvent.Warning })
        val result = assertIs<AgentEvent.ExecutionCompleted>(mapper.map(buildJsonObject { put("type", "execution_completed") })[1]).result
        assertEquals(StopReason.TURN_LIMIT, result.stopReason)
    }

    @Test
    fun `loop detection and provider stop are stop reasons`() {
        val a = GeminiEventMapper(ExecutionId("a"))
        a.map(buildJsonObject { put("type", "loop_detected") })
        assertEquals(StopReason.LOOP_DETECTED, assertIs<AgentEvent.ExecutionCompleted>(a.map(buildJsonObject { put("type", "execution_completed") })[1]).result.stopReason)
        val b = GeminiEventMapper(ExecutionId("b"))
        b.map(buildJsonObject { put("type", "agent_execution_stopped"); put("value", buildJsonObject { put("reason", "stopped") }) })
        assertEquals(StopReason.PROVIDER_STOPPED, assertIs<AgentEvent.ExecutionCompleted>(b.map(buildJsonObject { put("type", "execution_completed") })[1]).result.stopReason)
    }

    @Test
    fun `context pressure is a typed warning and colours a following error`() {
        val warning = assertIs<AgentEvent.Warning>(mapper.map(buildJsonObject { put("type", "context_window_will_overflow") }).first())
        assertEquals(WarningKind.CONTEXT_PRESSURE, warning.kind)
        val failed = assertIs<AgentEvent.ExecutionFailed>(mapper.map(buildJsonObject { put("type", "error"); put("value", buildJsonObject { put("message", "boom") }) }).first())
        assertEquals(FailureKind.CONTEXT_OVERFLOW, failed.kind)
    }

    @Test
    fun `blocked execution is policy blocked and plain errors are provider failures`() {
        assertEquals(FailureKind.POLICY_BLOCKED, assertIs<AgentEvent.ExecutionFailed>(GeminiEventMapper(ExecutionId("x")).map(buildJsonObject { put("type", "agent_execution_blocked") }).first()).kind)
        assertEquals(FailureKind.PROVIDER, assertIs<AgentEvent.ExecutionFailed>(GeminiEventMapper(ExecutionId("y")).map(buildJsonObject { put("type", "error"); put("value", "nope") }).first()).kind)
    }

    @Test
    fun `cancelled tool work keeps its own status`() {
        val events = mapper.map(buildJsonObject {
            put("type", "tool_call_response")
            put("value", buildJsonObject { put("callId", "c"); put("name", "write_file"); put("status", "cancelled") })
        })
        assertEquals(WorkStatus.CANCELLED, assertIs<AgentEvent.ToolCallChanged>(events[0]).status)
    }
}
