package dev.harnessprotocol.codex

import dev.harnessprotocol.*
import kotlinx.coroutines.CoroutineStart

import dev.harnessprotocol.testkit.Envelope.string
import dev.harnessprotocol.testkit.RecordingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A1/A2 behaviour of the Codex adapter against the host's interaction envelope. */
class CodexInteractionTest {
    private val callerDecides = SessionSpec(requirements = SessionRequirements(approval = ApprovalRequirement.CallerDecides))

    private fun requested(id: String = "turn-1#1", decisions: List<String> = listOf("accept", "acceptForSession", "decline", "cancel")) =
        notification("interaction_requested", buildJsonObject {
            put("interactionId", id)
            put("kind", "approval")
            put("effect", "command")
            put("workId", "item-9")
            put("prompt", "rm -rf build")
            put("availableDecisions", buildJsonArray { decisions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("detail", buildJsonObject { put("command", "rm -rf build") })
        })

    private fun resolved(id: String, resolution: JsonObject) =
        notification("interaction_resolved", buildJsonObject { put("interactionId", id); put("resolution", resolution) })

    private fun withHarness(block: suspend (RecordingBridge, CodexHarness) -> Unit) = runBlocking<Unit> {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { block(bridge, it) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test
    fun `caller decides is compatible`() = withHarness { _, harness ->
        assertTrue(harness.validate(callerDecides).isCompatible)
    }

    @Test
    fun `interaction request suspends and respond resumes`() = withHarness { bridge, harness ->
        val execution = harness.createSession(callerDecides).startTask(TaskRequest(TaskInput.Text("go")))
        assertEquals("caller_decides", bridge.paramsOf("create_session").single().string("approval"))
        bridge.emit(notification("turn/started"))
        bridge.emit(requested())

        waitFor { execution.state.value == TaskState.AWAITING_RESPONSE }
        val request = assertIs<InteractionRequest.Approval>(execution.pendingInteractions.value.single())
        assertEquals(EffectKind.COMMAND, request.effect)
        assertEquals("rm -rf build", request.prompt)
        assertEquals(setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE, ApprovalDecision.CANCEL), request.availableDecisions)

        execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
        val sent = bridge.paramsOf("respond_interaction").single()
        assertEquals("turn-1#1", sent.string("interactionId"))
        assertEquals("approve_once", sent["response"]!!.jsonObject.string("decision"))

        bridge.emit(resolved("turn-1#1", buildJsonObject { put("type", "responded"); put("decision", "approve_once") }))
        waitFor { execution.state.value == TaskState.RUNNING }
        assertTrue(execution.pendingInteractions.value.isEmpty())

        bridge.emit(notification("turn/completed", buildJsonObject { put("turn", buildJsonObject { put("status", "completed") }) }))
        withTimeout(5_000) { execution.awaitOutcome() }
    }

    @Test
    fun `interaction can be cleared without a response`() = withHarness { bridge, harness ->
        val execution = harness.createSession(callerDecides).startTask(TaskRequest(TaskInput.Text("go")))
        bridge.emit(notification("turn/started"))
        val seen = java.util.concurrent.CopyOnWriteArrayList<TaskEvent>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { execution.events.collect { seen += it } }
        bridge.emit(requested())
        waitFor { execution.state.value == TaskState.AWAITING_RESPONSE }
        bridge.emit(resolved("turn-1#1", buildJsonObject { put("type", "cleared"); put("reason", "turn_interrupted") }))
        waitFor { execution.state.value == TaskState.RUNNING }
        assertTrue(execution.pendingInteractions.value.isEmpty())
        waitFor { seen.any { it is TaskEvent.InteractionResolved } }
        collector.cancel()
        val cleared = seen.filterIsInstance<TaskEvent.InteractionResolved>().single()
        assertEquals(InteractionResolution.Cleared(ClearReason.CANCELLATION_REQUESTED), cleared.resolution)
    }

    @Test
    fun `rejects invalid or duplicate interaction response`() = withHarness { bridge, harness ->
        val execution = harness.createSession(callerDecides).startTask(TaskRequest(TaskInput.Text("go")))
        bridge.emit(notification("turn/started"))
        bridge.emit(requested(decisions = listOf("accept", "decline")))
        waitFor { execution.state.value == TaskState.AWAITING_RESPONSE }
        val id = execution.pendingInteractions.value.single().interactionId

        assertFailsWith<IllegalStateException> { execution.respond(InteractionId("nope"), InteractionResponse.Approval(ApprovalDecision.DECLINE)) }
        assertFailsWith<IllegalArgumentException> { execution.respond(id, InteractionResponse.Approval(ApprovalDecision.APPROVE_FOR_SESSION)) }
        assertTrue(bridge.paramsOf("respond_interaction").isEmpty())

        execution.respond(id, InteractionResponse.Approval(ApprovalDecision.DECLINE))
        bridge.emit(resolved(id.value, buildJsonObject { put("type", "responded"); put("decision", "decline") }))
        waitFor { execution.pendingInteractions.value.isEmpty() }
        assertFailsWith<IllegalStateException> { execution.respond(id, InteractionResponse.Approval(ApprovalDecision.DECLINE)) }
        assertEquals(1, bridge.paramsOf("respond_interaction").size)
    }

    @Test
    fun `cancel while waiting clears the request and the execution ends cancelled`() = withHarness { bridge, harness ->
        val execution = harness.createSession(callerDecides).startTask(TaskRequest(TaskInput.Text("go")))
        bridge.emit(notification("turn/started"))
        bridge.emit(requested())
        waitFor { execution.state.value == TaskState.AWAITING_RESPONSE }
        execution.requestCancellation()
        // The host clears the interaction first, then the turn ends interrupted.
        bridge.emit(resolved("turn-1#1", buildJsonObject { put("type", "cleared"); put("reason", "turn_interrupted") }))
        bridge.emit(notification("turn/completed", buildJsonObject { put("turn", buildJsonObject { put("status", "interrupted") }) }))
        assertIs<TaskOutcome.Cancelled>(withTimeout(5_000) { execution.awaitOutcome() })
        assertTrue(execution.pendingInteractions.value.isEmpty())
    }

    @Test
    fun `harness close while waiting clears the snapshot`() = runBlocking<Unit> {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val harness = CodexHarness.usingBridge(bridge, scope)
        val execution = harness.createSession(callerDecides).startTask(TaskRequest(TaskInput.Text("go")))
        bridge.emit(notification("turn/started"))
        bridge.emit(requested())
        waitFor { execution.state.value == TaskState.AWAITING_RESPONSE }
        harness.close()
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
        assertTrue(execution.pendingInteractions.value.isEmpty())
        scope.cancel()
    }
}
