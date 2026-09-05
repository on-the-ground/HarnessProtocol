package dev.harnessprotocol.testkit

import dev.harnessprotocol.*
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
 * SDK projection, transport calls and resource routing regressions for process adapters.
 *
 * Subclasses provide the harness factory, the intent projection, and a
 * provider event fixture. Pure lifecycle assertions live in harness-conformance.
 */
abstract class SdkAdapterContractTest : dev.harnessprotocol.conformance.HarnessLifecycleConformanceTest() {
    protected abstract fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness
    protected abstract fun projection(): IntentProjection
    protected abstract fun fixture(): ProviderFixture

    /** A spec every adapter accepts; used for lifecycle tests. */
    override fun compatibleSpec(): SessionSpec = SessionSpec()

    private val terminalStates = TaskState.entries.filter { it.isTerminal }.toSet()

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
        withSdkHarness { bridge, h ->
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
    fun `cancellation is reported through state and awaitOutcome`() = runBlocking<Unit> {
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            bridge.emitAll(fixture().started())
            bridge.emitAll(fixture().completed("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            execution.requestCancellation()
            assertTrue("cancel_execution" !in bridge.methods)
        }
    }

    @Test
    fun `stream ending without a terminal leaves outcome unresolved`() = runBlocking<Unit> {
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
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
    fun `session release is idempotent and rejects further tasks`() = runBlocking<Unit> {
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
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
        withSdkHarness { bridge, h ->
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

    final override fun lifecycleFixture(): dev.harnessprotocol.conformance.LifecycleFixture {
        val bridge = RecordingBridge()
        val scope = newScope()
        val h = try { harness(bridge, scope) } catch (failure: Throwable) { scope.cancel(); throw failure }
        return object : dev.harnessprotocol.conformance.LifecycleFixture {
            override val harness: AgentHarness = h
            override fun control(task: AgentTask) = object : dev.harnessprotocol.conformance.TaskLifecycleControl {
                private suspend fun emit(events: List<JsonObject>) { events.forEach { bridge.emit(task.id.value, it) } }
                override suspend fun reportRunning() { emit(fixture().started()) }
                override suspend fun reportMessageDelta(messageKey: String, text: String, role: dev.harnessprotocol.conformance.MessageKind?) {
                    require(role == null) { "This lifecycle binding does not control message roles" }
                    emit(fixture().messageDelta(text))
                }
                override suspend fun reportCompletion(output: dev.harnessprotocol.conformance.OutputObservation?, stopReason: StopReason) {
                    require(output is dev.harnessprotocol.conformance.OutputObservation.Text && output.complete)
                    require(stopReason == StopReason.FINISHED)
                    emit(fixture().completed(output.text))
                }
                override suspend fun reportFailure(message: String, kind: FailureKind?) {
                    require(kind == null) { "Use the SDK mapper suite to supply structured failure codes" }
                    emit(fixture().failed(message))
                }
                override suspend fun reportCancelledTermination() { emit(fixture().cancelled()) }
            }
            override fun close() { try { h.close() } finally { scope.cancel() } }
        }
    }

    // --------------------------------------------------------------- helpers

    protected suspend fun withSdkHarness(block: suspend (RecordingBridge, AgentHarness) -> Unit) {
        val bridge = RecordingBridge()
        val scope = newScope()
        try {
            harness(bridge, scope).use { block(bridge, it) }
        } finally {
            scope.cancel()
        }
    }

}
