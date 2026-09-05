package dev.harnessprotocol.testkit

import dev.harnessprotocol.*
import kotlinx.coroutines.CoroutineStart
import kotlin.test.assertIs
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
    protected open fun compatibleSpec(): SessionSpec = SessionSpec()

    protected val terminalStates = TaskState.entries.filter { it.isTerminal }.toSet()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected suspend fun RecordingBridge.emitAll(events: List<JsonObject>) = events.forEach { emit(it) }

    // ---------------------------------------------------------------- intent

    @Test
    fun `compatible specs reach the bridge with their intent intact`() = runBlocking<Unit> {
        var checked = 0
        for (spec in SpecSpace.all()) {
            val bridge = RecordingBridge()
            val scope = newScope()
            try {
                val h = harness(bridge, scope)
                h.use {
                    val report = h.validate(spec)
                    when (report.status) {
                        CompatibilityStatus.COMPATIBLE -> {
                            h.createSession(spec)
                            val sent = bridge.paramsOf("create_session").single()
                            projection().assertPreserved(spec, sent)
                        }
                        CompatibilityStatus.INCOMPATIBLE ->
                            assertFailsWith<IncompatibleRequirementException>("spec $spec") { h.createSession(spec) }
                        CompatibilityStatus.UNCONFIRMED ->
                            assertFailsWith<RequirementUnconfirmedException>("spec $spec") { h.createSession(spec) }
                    }
                    if (!report.isCompatible)
                        assertTrue(bridge.paramsOf("create_session").isEmpty(), "rejected spec must not reach the bridge: $spec")
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
    fun `creates a session and completes an execution`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.startTask(TaskRequest(TaskInput.Text("hello")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("world"))
            val result = assertIs<TaskOutcome.Completed>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals("world", assertIs<TaskOutput.Text>(result.output).text)
            assertEquals(TaskState.COMPLETED, execution.state.value)
            assertEquals("session-1", session.id.value)
            assertEquals(listOf("create_session", "start_execution"), bridge.methods)
        }
    }

    @Test
    fun `state is terminal before awaitOutcome returns`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            assertTrue(execution.state.value in terminalStates)
        }
    }

    @Test
    fun `completes without an event collector`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            repeat(500) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
        }
    }

    @Test
    fun `slow collector does not block lifecycle`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val gate = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { gate.await() }   // subscribed, but never makes progress
            }
            bridge.emitAll(fixture().started())
            repeat(2_000) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            assertEquals(TaskState.COMPLETED, execution.state.value)
            gate.complete(Unit)
            collector.cancel()
        }
    }

    @Test
    fun `failure is reported through state and awaitOutcome`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().failed("boom"))
            val failure = assertIs<TaskOutcome.Failed>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals("boom", failure.message)
            assertEquals(TaskState.FAILED, execution.state.value)
        }
    }

    @Test
    fun `cancellation is reported through state and awaitOutcome`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            execution.requestCancellation()
            assertTrue("cancel_execution" in bridge.methods)
            bridge.emitAll(fixture().cancelled())
            assertIs<TaskOutcome.Cancelled>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals(TaskState.CANCELLED, execution.state.value)
        }
    }

    @Test
    fun `cancel after terminal is a no-op`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            execution.requestCancellation()
            assertTrue("cancel_execution" !in bridge.methods)
        }
    }

    @Test
    fun `completion wins the race against cancel`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            execution.requestCancellation()
            bridge.emitAll(fixture().completed("done"))
            assertEquals("done", assertIs<TaskOutput.Text>(withTimeout(5_000) { execution.awaitOutcome() }.output).text)
            assertEquals(TaskState.COMPLETED, execution.state.value)
        }
    }

    @Test
    fun `terminal is exactly once and last`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val seen = java.util.concurrent.CopyOnWriteArrayList<TaskEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { execution.events.collect { seen += it } }
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            bridge.emitAll(fixture().failed("late"))     // duplicate terminal must be ignored
            withTimeout(5_000) { execution.awaitOutcome() }
            waitUntil { seen.any { it is TaskEvent.TaskCompleted } }
            collector.cancel()
            val terminalIndex = seen.indexOfFirst { it.isTerminal() }
            assertEquals(1, seen.count { it.isTerminal() }, "exactly one terminal event: $seen")
            assertEquals(seen.lastIndex, terminalIndex, "terminal must be the last event: $seen")
            assertEquals(TaskState.COMPLETED, execution.state.value)
        }
    }

    @Test
    fun `stream ending without a terminal leaves outcome unresolved`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.endStream(bridge.lastExecutionId!!)
            val outcome = assertIs<TaskOutcome.Unresolved>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals(UnresolvedReason.OBSERVATION_LOST, outcome.reason)
            assertEquals(TaskState.UNRESOLVED, execution.state.value)
            assertFailsWith<SessionBlockedException> { session.startTask(TaskRequest(TaskInput.Text("unsafe retry"))) }
            assertEquals(1, bridge.paramsOf("start_execution").size)
        }
    }

    @Test
    fun `stream failure leaves outcome unresolved`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.failStream(bridge.lastExecutionId!!)
            val outcome = assertIs<TaskOutcome.Unresolved>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals(UnresolvedReason.OBSERVATION_LOST, outcome.reason)
            assertEquals(TaskState.UNRESOLVED, execution.state.value)
            assertFailsWith<SessionBlockedException> { session.startTask(TaskRequest(TaskInput.Text("unsafe retry"))) }
            assertEquals(1, bridge.paramsOf("start_execution").size)
        }
    }

    @Test
    fun `release is called after terminal`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            waitUntil { bridge.released.isNotEmpty() }
            assertEquals(listOf(bridge.lastExecutionId), bridge.released)
        }
    }

    @Test
    fun `rejects overlapping tasks on one session`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val first = session.startTask(TaskRequest(TaskInput.Text("a")))
            bridge.emitAll(fixture().started())
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("b"))) }
            assertEquals(1, bridge.paramsOf("start_execution").size)
            first.requestCancellation()
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("c"))) }   // cancel requested, not terminal
            bridge.emitAll(fixture().cancelled())
            runCatching { withTimeout(5_000) { first.awaitOutcome() } }
            session.startTask(TaskRequest(TaskInput.Text("d")))                                             // terminal → allowed
            assertEquals(2, bridge.paramsOf("start_execution").size)
        }
    }

    @Test
    fun `different sessions execute concurrently`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val a = h.createSession(compatibleSpec())
            val b = h.createSession(compatibleSpec())
            val ea = a.startTask(TaskRequest(TaskInput.Text("a")))
            val idA = bridge.lastExecutionId!!
            val eb = b.startTask(TaskRequest(TaskInput.Text("b")))
            val idB = bridge.lastExecutionId!!
            fixture().started().forEach { bridge.emit(idA, it); bridge.emit(idB, it) }
            fixture().completed("A").forEach { bridge.emit(idA, it) }
            fixture().completed("B").forEach { bridge.emit(idB, it) }
            assertEquals("A", assertIs<TaskOutput.Text>(withTimeout(5_000) { ea.awaitOutcome() }.output).text)
            assertEquals("B", assertIs<TaskOutput.Text>(withTimeout(5_000) { eb.awaitOutcome() }.output).text)
        }
    }

    @Test
    fun `harness close without native termination evidence settles unresolved`() = runBlocking<Unit> {
        val bridge = RecordingBridge()
        val scope = newScope()
        val h = harness(bridge, scope)
        val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
        bridge.emitAll(fixture().started())
        h.close()
        assertIs<TaskOutcome.Unresolved>(withTimeout(5_000) { execution.awaitOutcome() })
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
        scope.cancel()
    }

    @Test
    fun `overflow is explicit and terminal survives`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val gate = CompletableDeferred<Unit>()
            val seen = java.util.concurrent.CopyOnWriteArrayList<TaskEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { event ->
                    seen += event
                    if (seen.size == 1) gate.await()   // stall after the first event so the queue overflows
                }
            }
            bridge.emitAll(fixture().started())
            val total = 5_000
            repeat(total) { bridge.emitAll(fixture().messageDelta("chunk$it ")) }
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            gate.complete(Unit)
            waitUntil { seen.lastOrNull()?.isTerminal() == true }
            collector.cancel()

            val gaps = seen.filterIsInstance<TaskEvent.ObservationGap>()
            assertTrue(gaps.isNotEmpty(), "a stalled collector must see an ObservationGap")
            assertTrue(seen.last().isTerminal(), "terminal must be delivered last after the gap")
            assertEquals(1, seen.count { it.isTerminal() })
        }
    }

    @Test
    fun `session release is idempotent and rejects further tasks`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            session.release()
            session.release()
            assertEquals(1, bridge.methods.count { it == "release_session" })
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("x"))) }
            assertTrue(bridge.paramsOf("start_execution").isEmpty())
        }
    }

    @Test
    fun `session release settles an active execution`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val session = h.createSession(compatibleSpec())
            val execution = session.startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            session.release()
            assertTrue("cancel_execution" in bridge.methods)
            assertTrue(execution.state.value in terminalStates, "state after release: ${execution.state.value}")
            assertIs<TaskOutcome.Unresolved>(withTimeout(5_000) { execution.awaitOutcome() })
        }
    }

    @Test
    fun `reopen uses the session id returned by the host`() = runBlocking<Unit> {
        withHarness { bridge, h ->
            val spec = compatibleSpec().copy(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
            val original = h.createSession(spec)
            val ref = requireNotNull(original.persistentRef)
            original.release()
            bridge.respondTo("resume_session") { buildJsonObject { put("sessionId", "normalized-42") } }
            val session = assertIs<PersistentSessions>(h).reopenSession(ref, spec)
            assertEquals("normalized-42", session.id.value)
            session.release()
            val reopened = h.reopenSession(requireNotNull(session.persistentRef), spec)
            assertEquals("normalized-42", reopened.id.value)
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

    protected fun TaskEvent.isTerminal() =
        this is TaskEvent.Terminal

    protected suspend fun AgentSession.executeText(text: String): AgentTask = startTask(TaskRequest(TaskInput.Text(text)))
}
