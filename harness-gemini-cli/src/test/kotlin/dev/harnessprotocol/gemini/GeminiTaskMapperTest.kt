package dev.harnessprotocol.gemini

import dev.harnessprotocol.*
import dev.harnessprotocol.testkit.TaskMappingProbe
import kotlin.test.AfterTest
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GeminiTaskMapperTest {
    private val probes = mutableListOf<TaskMappingProbe>()
    private fun mapperFor(id: TaskId) = TaskMappingProbe(id) { GeminiTaskMapper(it, SessionSpec())::accept }.also { probes += it }
    @AfterTest fun cleanup() { probes.forEach { it.close() } }
    private val mapper = mapperFor(TaskId("execution-1"))

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

        val completed = assertIs<TaskEvent.TaskCompleted>(events.single())
        assertEquals("hello world", assertIs<TaskOutput.Text>(completed.outcome.output).text)
        assertEquals(StopReason.FINISHED, completed.outcome.stopReason)
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

        val tool = assertIs<TaskEvent.ToolCallChanged>(events[0])
        val effect = assertIs<TaskEvent.EffectChanged>(events[1])
        assertEquals("./gradlew test", Json.parseToJsonElement(requireNotNull(tool.arguments)).jsonObject["command"]?.jsonPrimitive?.content)
        assertEquals(EffectKind.COMMAND, effect.kind)
        assertEquals("./gradlew test", effect.description)
    }

    @Test
    fun `accumulates usage across multiple finished events`() {
        mapper.map(finished(prompt = 10, candidates = 5, total = 15))
        assertTrue(!mapper.task.state.value.isTerminal)
        val second = assertIs<TaskEvent.UsageChanged>(mapper.map(finished(prompt = 20, candidates = 7, total = 27)).first())
        assertEquals(30, second.task.inputTokens)
        assertEquals(12, second.task.outputTokens)
        assertEquals(42, second.task.totalTokens)
        assertNull(second.session)
        val result = assertIs<TaskEvent.TaskCompleted>(mapper.map(buildJsonObject { put("type", "execution_completed") }).single()).outcome
        assertEquals(42, result.usage.totalTokens)
    }

    @Test
    fun `turn limit completes with a stop reason instead of a warning`() {
        val warnings = mapper.map(buildJsonObject { put("type", "max_session_turns"); put("value", "limit") })
        assertNull(warnings.firstOrNull { it is TaskEvent.Warning })
        val result = assertIs<TaskEvent.TaskCompleted>(mapper.map(buildJsonObject { put("type", "execution_completed") }).single()).outcome
        assertEquals(StopReason.ITERATION_LIMIT, result.stopReason)
    }

    @Test
    fun `loop detection and provider stop are stop reasons`() {
        val a = mapperFor(TaskId("a"))
        a.map(buildJsonObject { put("type", "loop_detected") })
        assertEquals(StopReason.LOOP_DETECTED, assertIs<TaskEvent.TaskCompleted>(a.map(buildJsonObject { put("type", "execution_completed") }).single()).outcome.stopReason)
        val b = mapperFor(TaskId("b"))
        b.map(buildJsonObject { put("type", "agent_execution_stopped"); put("value", buildJsonObject { put("reason", "stopped") }) })
        assertEquals(StopReason.PROVIDER_STOPPED, assertIs<TaskEvent.TaskCompleted>(b.map(buildJsonObject { put("type", "execution_completed") }).single()).outcome.stopReason)
    }

    @Test
    fun `context pressure and inner errors cannot terminate the owned invocation`() {
        val warning = assertIs<TaskEvent.Warning>(mapper.map(buildJsonObject { put("type", "context_window_will_overflow") }).single())
        assertEquals(WarningKind.CONTEXT_PRESSURE, warning.kind)
        val events = mapper.map(buildJsonObject { put("type", "error"); put("value", buildJsonObject { put("message", "boom") }) })
        assertTrue(events.none { it is TaskEvent.Terminal })
        assertTrue(!mapper.task.state.value.isTerminal)
        val failed = assertIs<TaskEvent.TaskFailed>(mapper.map(buildJsonObject { put("type", "execution_failed"); put("value", "boom") }).single())
        assertEquals(FailureKind.UNKNOWN, failed.outcome.kind)
    }

    @Test
    fun `only host-confirmed failure carries structured policy classification`() {
        assertTrue(mapper.map(buildJsonObject { put("type", "agent_execution_blocked") }).none { it is TaskEvent.Terminal })
        val failed = assertIs<TaskEvent.TaskFailed>(mapper.map(buildJsonObject {
            put("type", "execution_failed")
            put("value", buildJsonObject { put("message", "blocked"); put("failureKind", "policy_blocked") })
        }).single())
        assertEquals(FailureKind.POLICY_BLOCKED, failed.outcome.kind)
    }

    @Test
    fun `cancelled tool work keeps its own status`() {
        val events = mapper.map(buildJsonObject {
            put("type", "tool_call_response")
            put("value", buildJsonObject { put("callId", "c"); put("name", "write_file"); put("status", "cancelled") })
        })
        assertEquals(WorkStatus.CANCELLED, assertIs<TaskEvent.ToolCallChanged>(events[0]).status)
    }
}
