package dev.harnessprotocol.codex

import dev.harnessprotocol.legacy.AgentEvent
import dev.harnessprotocol.legacy.AgentUsage
import dev.harnessprotocol.legacy.ExecutionId
import dev.harnessprotocol.legacy.FailureKind
import dev.harnessprotocol.legacy.MessagePhase
import dev.harnessprotocol.legacy.WarningKind
import dev.harnessprotocol.legacy.WorkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CodexEventMapperTest {
    private val mapper = CodexEventMapper(ExecutionId("turn-1"))

    private fun raw(method: String, payload: JsonObject) = buildJsonObject {
        put("method", method)
        put("payload", payload)
    }

    private fun agentMessage(id: String, text: String, phase: String) = raw("item/completed", buildJsonObject {
        put("item", buildJsonObject { put("id", id); put("type", "agentMessage"); put("text", text); put("phase", phase) })
    })

    private fun turnCompleted(status: String = "completed", error: JsonObject? = null) = raw("turn/completed", buildJsonObject {
        put("turn", buildJsonObject { put("status", status); error?.let { put("error", it) } })
    })

    private fun usage(total: Triple<Long, Long, Long>, last: Triple<Long, Long, Long>) = raw("thread/tokenUsage/updated", buildJsonObject {
        put("tokenUsage", buildJsonObject {
            put("total", buildJsonObject { put("inputTokens", total.first); put("outputTokens", total.second); put("totalTokens", total.third); put("cachedInputTokens", 0); put("reasoningOutputTokens", 0) })
            put("last", buildJsonObject { put("inputTokens", last.first); put("outputTokens", last.second); put("totalTokens", last.third); put("cachedInputTokens", 0); put("reasoningOutputTokens", 0) })
        })
    })

    @Test
    fun `maps final message and completion`() {
        val itemEvents = mapper.map(agentMessage("item-1", "done", "final_answer"))
        assertEquals("done", assertIs<AgentEvent.MessageCompleted>(itemEvents.first()).text)

        val turnEvents = mapper.map(turnCompleted())
        assertEquals("done", assertIs<AgentEvent.ExecutionCompleted>(turnEvents.first()).result.finalMessage)
    }

    @Test
    fun `commentary after the final answer does not overwrite the final message`() {
        mapper.map(agentMessage("m1", "the answer", "final_answer"))
        val commentary = mapper.map(agentMessage("m2", "just checking", "commentary"))
        assertEquals(MessagePhase.PROGRESS, assertIs<AgentEvent.MessageCompleted>(commentary.first()).phase)
        val result = assertIs<AgentEvent.ExecutionCompleted>(mapper.map(turnCompleted()).first()).result
        assertEquals("the answer", result.finalMessage)
    }

    @Test
    fun `reads nested last and total usage`() {
        val events = mapper.map(usage(total = Triple(100, 40, 140), last = Triple(30, 10, 40)))
        val changed = assertIs<AgentEvent.UsageChanged>(events.first())
        assertEquals(AgentUsage(inputTokens = 30, cachedInputTokens = 0, outputTokens = 10, reasoningTokens = 0, totalTokens = 40), changed.execution)
        assertEquals(140, changed.session?.totalTokens)
    }

    @Test
    fun `execution usage excludes previous executions of the thread`() {
        // Thread already consumed 100 tokens before this turn; two model calls happen in the turn.
        mapper.map(usage(total = Triple(120, 50, 170), last = Triple(20, 10, 30)))
        val second = assertIs<AgentEvent.UsageChanged>(mapper.map(usage(total = Triple(145, 65, 210), last = Triple(25, 15, 40))).first())
        assertEquals(70, second.execution.totalTokens)   // 30 + 40, not 210
        assertEquals(210, second.session?.totalTokens)
        val result = assertIs<AgentEvent.ExecutionCompleted>(mapper.map(turnCompleted()).first()).result
        assertEquals(70, result.usage?.totalTokens)
        assertEquals(210, result.sessionUsage?.totalTokens)
    }

    @Test
    fun `usage without total is ignored rather than mapped to nulls`() {
        val events = mapper.map(raw("thread/tokenUsage/updated", buildJsonObject { put("tokenUsage", buildJsonObject { }) }))
        assertNull(events.firstOrNull { it is AgentEvent.UsageChanged })
    }

    @Test
    fun `classifies structured error codes`() {
        fun kind(info: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = assertIs<AgentEvent.ExecutionFailed>(
            CodexEventMapper(ExecutionId("t")).map(raw("error", buildJsonObject { put("error", buildJsonObject { put("message", "x"); info() }) })).first(),
        ).kind
        assertEquals(FailureKind.CONTEXT_OVERFLOW, kind { put("codexErrorInfo", "contextWindowExceeded") })
        assertEquals(FailureKind.BUDGET_EXCEEDED, kind { put("codexErrorInfo", "usageLimitExceeded") })
        assertEquals(FailureKind.AUTHENTICATION, kind { put("codexErrorInfo", "unauthorized") })
        assertEquals(FailureKind.POLICY_BLOCKED, kind { put("codexErrorInfo", "sandboxError") })
        assertEquals(FailureKind.TRANSIENT, kind { put("codexErrorInfo", "serverOverloaded") })
        assertEquals(FailureKind.TRANSIENT, kind { put("codexErrorInfo", buildJsonObject { put("httpConnectionFailed", buildJsonObject { put("httpStatusCode", 503) }) }) })
        assertEquals(FailureKind.AUTHENTICATION, kind { put("codexErrorInfo", buildJsonObject { put("httpConnectionFailed", buildJsonObject { put("httpStatusCode", 401) }) }) })
        assertEquals(FailureKind.PROVIDER, kind { })
        assertEquals(FailureKind.UNKNOWN, kind { put("codexErrorInfo", "somethingNew") })
    }

    @Test
    fun `an error the provider will retry is a warning not a failure`() {
        val events = mapper.map(raw("error", buildJsonObject { put("willRetry", true); put("error", buildJsonObject { put("message", "retrying") }) }))
        assertEquals(WarningKind.RECOVERABLE, assertIs<AgentEvent.Warning>(events.first()).kind)
    }

    @Test
    fun `failed turn carries the turn error classification`() {
        val events = mapper.map(turnCompleted("failed", buildJsonObject { put("message", "boom"); put("codexErrorInfo", "cyberPolicy") }))
        val failed = assertIs<AgentEvent.ExecutionFailed>(events.first())
        assertEquals(FailureKind.POLICY_BLOCKED, failed.kind)
        assertEquals("boom", failed.message)
    }

    @Test
    fun `interrupted command work is cancelled not failed`() {
        val events = mapper.map(raw("item/completed", buildJsonObject {
            put("item", buildJsonObject { put("id", "c1"); put("type", "commandExecution"); put("status", "interrupted"); put("command", "sleep 100") })
        }))
        assertEquals(WorkStatus.CANCELLED, assertIs<AgentEvent.EffectChanged>(events.first()).status)
    }
}
