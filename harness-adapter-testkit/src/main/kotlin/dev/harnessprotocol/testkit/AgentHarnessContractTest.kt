package dev.harnessprotocol.testkit

import dev.harnessprotocol.AgentEvent
import dev.harnessprotocol.AgentExecution
import dev.harnessprotocol.AgentExecutionCancelledException
import dev.harnessprotocol.AgentExecutionException
import dev.harnessprotocol.AgentExecutionFailedException
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.AgentHarness
import dev.harnessprotocol.AgentInput
import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.AgentSpec
import dev.harnessprotocol.ExecutionState
import dev.harnessprotocol.IncompatibleAgentSpecException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lifecycle and intent contract every SDK adapter must satisfy.
 *
 * Subclasses provide the harness factory, the intent projection, and a
 * provider event fixture. Everything else is shared.
 */
abstract class AgentHarnessContractTest {
    protected abstract fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness
    protected abstract fun projection(): IntentProjection
    protected abstract fun fixture(): ProviderFixture

    /** A spec every adapter accepts; used for lifecycle tests. */
    protected open fun compatibleSpec(): AgentSpec = AgentSpec()

    protected val terminalStates = setOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED)

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected suspend fun RecordingBridge.emitAll(events: List<JsonObject>) = events.forEach { emit(it) }

    // ---------------------------------------------------------------- intent

    @Test
    fun `compatible specs reach the bridge with their intent intact`() = runBlocking {
        var checked = 0
        for (spec in SpecSpace.all()) {
            val bridge = RecordingBridge()
            val scope = newScope()
            try {
                val h = harness(bridge, scope)
                h.use {
                    val report = h.validate(spec)
                    if (report.isCompatible) {
                        h.createSession(spec)
                        val sent = bridge.paramsOf("create_session").single()
                        projection().assertPreserved(spec, sent)
                    } else {
                        assertFailsWith<IncompatibleAgentSpecException>("spec $spec") { h.createSession(spec) }
                        assertTrue(bridge.paramsOf("create_session").isEmpty(), "rejected spec must not reach the bridge: $spec")
                    }
                }
            } finally {
                scope.cancel()
            }
            checked++
        }
        assertTrue(checked > 100, "spec space unexpectedly small: $checked")
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `creates a session and completes an execution`() = runBlocking {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.execute(AgentInput.Text("hello"))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("world"))
            val result = withTimeout(5_000) { execution.awaitResult() }
            assertEquals("world", result.finalMessage)
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
            assertEquals("session-1", session.id.value)
            assertEquals(listOf("create_session", "start_execution"), bridge.methods)
        }
    }

    @Test
    fun `state is terminal before awaitResult returns`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
            assertTrue(execution.state.value in terminalStates)
        }
    }

    @Test
    fun `completes without an event collector`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            repeat(500) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
        }
    }

    @Test
    fun `slow collector does not block lifecycle`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            val gate = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                execution.events.collect { gate.await() }   // subscribed, but never makes progress
            }
            bridge.emitAll(fixture().started())
            repeat(2_000) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
            gate.complete(Unit)
            collector.cancel()
        }
    }

    @Test
    fun `failure is reported through state and awaitResult`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().failed("boom"))
            val failure = assertFailsWith<AgentExecutionFailedException> { withTimeout(5_000) { execution.awaitResult() } }
            assertEquals("boom", failure.message)
            assertEquals(ExecutionState.FAILED, execution.state.value)
        }
    }

    @Test
    fun `cancellation is reported through state and awaitResult`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            execution.cancel()
            assertTrue("cancel_execution" in bridge.methods)
            bridge.emitAll(fixture().cancelled())
            assertFailsWith<AgentExecutionCancelledException> { withTimeout(5_000) { execution.awaitResult() } }
            assertEquals(ExecutionState.CANCELLED, execution.state.value)
        }
    }

    @Test
    fun `cancel after terminal is a no-op`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
            execution.cancel()
            assertTrue("cancel_execution" !in bridge.methods)
        }
    }

    @Test
    fun `completion wins the race against cancel`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            execution.cancel()
            bridge.emitAll(fixture().completed("done"))
            assertEquals("done", withTimeout(5_000) { execution.awaitResult() }.finalMessage)
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
        }
    }

    @Test
    fun `terminal is exactly once and last`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            val seen = mutableListOf<AgentEvent>()
            val collector = launch(Dispatchers.Default) { execution.events.collect { seen += it } }
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            bridge.emitAll(fixture().failed("late"))     // duplicate terminal must be ignored
            withTimeout(5_000) { execution.awaitResult() }
            waitUntil { seen.any { it is AgentEvent.ExecutionCompleted } }
            collector.cancel()
            val terminalIndex = seen.indexOfFirst { it.isTerminal() }
            assertEquals(1, seen.count { it.isTerminal() }, "exactly one terminal event: $seen")
            assertEquals(seen.lastIndex, terminalIndex, "terminal must be the last event: $seen")
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
        }
    }

    @Test
    fun `stream ending without a terminal fails the execution as transport`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.endStream(bridge.lastExecutionId!!)
            val failure = assertFailsWith<AgentExecutionFailedException> { withTimeout(5_000) { execution.awaitResult() } }
            assertEquals(FailureKind.TRANSPORT, failure.kind)
            assertEquals(ExecutionState.FAILED, execution.state.value)
        }
    }

    @Test
    fun `stream failure fails the execution as transport`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.failStream(bridge.lastExecutionId!!)
            val failure = assertFailsWith<AgentExecutionFailedException> { withTimeout(5_000) { execution.awaitResult() } }
            assertEquals(FailureKind.TRANSPORT, failure.kind)
            assertEquals(ExecutionState.FAILED, execution.state.value)
        }
    }

    @Test
    fun `release is called after terminal`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
            waitUntil { bridge.released.isNotEmpty() }
            assertEquals(listOf(bridge.lastExecutionId), bridge.released)
        }
    }

    @Test
    fun `rejects overlapping execute on one session`() = runBlocking {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val first = session.execute(AgentInput.Text("a"))
            bridge.emitAll(fixture().started())
            assertFailsWith<IllegalStateException> { session.execute(AgentInput.Text("b")) }
            assertEquals(1, bridge.paramsOf("start_execution").size)
            first.cancel()
            assertFailsWith<IllegalStateException> { session.execute(AgentInput.Text("c")) }   // cancel requested, not terminal
            bridge.emitAll(fixture().cancelled())
            runCatching { withTimeout(5_000) { first.awaitResult() } }
            session.execute(AgentInput.Text("d"))                                             // terminal → allowed
            assertEquals(2, bridge.paramsOf("start_execution").size)
        }
    }

    @Test
    fun `different sessions execute concurrently`() = runBlocking {
        withHarness { bridge, h ->
            val a = h.createSession(compatibleSpec())
            val b = h.createSession(compatibleSpec())
            val ea = a.execute(AgentInput.Text("a"))
            val idA = bridge.lastExecutionId!!
            val eb = b.execute(AgentInput.Text("b"))
            val idB = bridge.lastExecutionId!!
            fixture().started().forEach { bridge.emit(idA, it); bridge.emit(idB, it) }
            fixture().completed("A").forEach { bridge.emit(idA, it) }
            fixture().completed("B").forEach { bridge.emit(idB, it) }
            assertEquals("A", withTimeout(5_000) { ea.awaitResult() }.finalMessage)
            assertEquals("B", withTimeout(5_000) { eb.awaitResult() }.finalMessage)
        }
    }

    @Test
    fun `harness close settles active executions as cancelled`() = runBlocking {
        val bridge = RecordingBridge()
        val scope = newScope()
        val h = harness(bridge, scope)
        val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
        bridge.emitAll(fixture().started())
        h.close()
        assertFailsWith<AgentExecutionCancelledException> { withTimeout(5_000) { execution.awaitResult() } }
        assertEquals(ExecutionState.CANCELLED, execution.state.value)
        scope.cancel()
    }

    @Test
    fun `overflow is explicit and terminal survives`() = runBlocking {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).execute(AgentInput.Text("x"))
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<AgentEvent>()
            val collector = launch(Dispatchers.Default) {
                execution.events.collect { event ->
                    seen += event
                    if (seen.size == 1) gate.await()   // stall after the first event so the queue overflows
                }
            }
            bridge.emitAll(fixture().started())
            val total = 5_000
            repeat(total) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitResult() }
            gate.complete(Unit)
            waitUntil { seen.lastOrNull()?.isTerminal() == true }
            collector.cancel()

            val gaps = seen.filterIsInstance<AgentEvent.ObservationGap>()
            assertTrue(gaps.isNotEmpty(), "a stalled collector must see an ObservationGap")
            assertTrue(seen.last().isTerminal(), "terminal must be delivered last after the gap")
            assertEquals(1, seen.count { it.isTerminal() })
        }
    }

    @Test
    fun `session release is idempotent and rejects further execute`() = runBlocking {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            session.release()
            session.release()
            assertEquals(1, bridge.methods.count { it == "release_session" })
            assertFailsWith<IllegalStateException> { session.execute(AgentInput.Text("x")) }
            assertTrue(bridge.paramsOf("start_execution").isEmpty())
        }
    }

    @Test
    fun `session release settles an active execution`() = runBlocking {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.execute(AgentInput.Text("x"))
            bridge.emitAll(fixture().started())
            session.release()
            assertTrue("cancel_execution" in bridge.methods)
            assertTrue(execution.state.value in terminalStates, "state after release: ${execution.state.value}")
            assertFailsWith<AgentExecutionException> { withTimeout(5_000) { execution.awaitResult() } }
        }
    }

    @Test
    fun `resume uses the session id returned by the host`() = runBlocking {
        withHarness { bridge, h ->
            bridge.respondTo("resume_session") { buildJsonObject { put("sessionId", "normalized-42") } }
            val session = h.resumeSession(dev.harnessprotocol.SessionId("raw-42"), compatibleSpec())
            assertEquals("normalized-42", session.id.value)
        }
    }

    // --------------------------------------------------------------- helpers

    protected suspend fun withHarness(block: suspend (RecordingBridge, AgentHarness) -> Unit) {
        val bridge = RecordingBridge()
        val scope = newScope()
        try {
            harness(bridge, scope).use { block(bridge, it) }
        } finally {
            scope.cancel()
        }
    }

    protected suspend fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) kotlinx.coroutines.delay(5)
        }
    }

    protected fun AgentEvent.isTerminal() =
        this is AgentEvent.ExecutionCompleted || this is AgentEvent.ExecutionFailed || this is AgentEvent.ExecutionCancelled

    protected suspend fun AgentSession.executeText(text: String): AgentExecution = execute(AgentInput.Text(text))
}
