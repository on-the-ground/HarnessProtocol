package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Timeout
import kotlin.time.measureTime
import kotlin.test.*

/**
 * Runs the real configured engine through a controlled model boundary.
 * Assertions inspect actual runtime contexts; provider configuration and wire formats
 * belong to the binding, not this suite.
 */
@Timeout(90)
abstract class HarnessRuntimeConformanceTest<B : RuntimeObservation> {
    protected abstract fun boundary(): B
    protected abstract fun harness(model: B): AgentHarness
    protected open fun spec() = SessionSpec(instructions = "AHP_NATIVE_INSTRUCTION", model = null)

    @Test fun `native task completes without an observer and preserves state and output`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val task = h.createSession(spec()).startTask(TaskRequest(TaskInput.Text("first marker-alpha")))
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Completed>(outcome)
            assertEquals(TaskState.COMPLETED, task.state.value)
            assertEquals("native-result", (outcome.output as TaskOutput.Text).text)
            assertTrue(task.pendingInteractions.value.isEmpty())
            assertTrue(model.observedContexts.any { "marker-alpha" in it }, "input must actually reach the model boundary")
            assertTrue(model.observedContexts.any { "AHP_NATIVE_INSTRUCTION" in it }, "instructions must reach native model configuration")
        } }
    }

    @Test fun `whitespace-only caller input reaches the native model without trimming`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val whitespace = " \t  "
            val task = h.createSession(spec()).startTask(TaskRequest(TaskInput.Text(whitespace)))
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertTrue(whitespace in model.observedTextValues,
                "The native model boundary must receive the exact whitespace-only TaskInput")
        } }
    }

    @Test fun `same session carries prior native context while a new session stays isolated`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            withTimeout(60_000) { session.startTask(TaskRequest(TaskInput.Text("remember marker-alpha"))).awaitOutcome() }.also { assertIs<TaskOutcome.Completed>(it) }
            val firstCount = model.observedContexts.size
            withTimeout(60_000) { session.startTask(TaskRequest(TaskInput.Text("followup marker-beta"))).awaitOutcome() }.also { assertIs<TaskOutcome.Completed>(it) }
            val subsequent = model.observedContexts.drop(firstCount).joinToString()
            assertTrue("marker-alpha" in subsequent, "prior caller input must be in the actual subsequent model context")
            assertTrue("native-result" in subsequent, "prior assistant output must be in the actual subsequent model context")
            val nextCount = model.observedContexts.size
            withTimeout(60_000) { h.createSession(spec()).startTask(TaskRequest(TaskInput.Text("independent marker-gamma"))).awaitOutcome() }
            val independent = model.observedContexts.drop(nextCount).joinToString()
            assertTrue("marker-gamma" in independent)
            assertFalse("marker-alpha" in independent, "independent sessions must not inherit previous context")
        } }
    }

    @Test fun `overlap is rejected and cancelling one waiter does not cancel native work`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            model.hold()
            val task = session.startTask(TaskRequest(TaskInput.Text("held native task")))
            withTimeout(60_000) { while (model.observedContexts.none { "held native task" in it }) delay(10) }
            val first = async { task.awaitOutcome() }
            val second = async { task.awaitOutcome() }
            first.cancelAndJoin()
            assertFalse(task.state.value.isTerminal)
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("must not be sent"))) }
            assertTrue(model.observedContexts.none { "must not be sent" in it })
            model.release()
            val outcome = withTimeout(60_000) { second.await() }
            assertIs<TaskOutcome.Completed>(outcome)
            assertEquals(outcome, task.awaitOutcome())
        } }
    }

    @Test fun `close bounds active native work and settles the waiter`() = runBlocking<Unit> {
        boundary().use { model ->
            val h = harness(model)
            try {
                val session = h.createSession(spec())
                model.hold()
                val task = session.startTask(TaskRequest(TaskInput.Text("hold until cleanup")))
                withTimeout(60_000) { while (model.observedContexts.none { "hold until cleanup" in it }) delay(10) }
                val elapsed = measureTime { withContext(Dispatchers.IO) { h.close() } }
                assertTrue(elapsed.inWholeMilliseconds <= h.cleanupBudget.total.inWholeMilliseconds + 2_000, "close exceeded its declared budget: $elapsed")
                val outcome = withTimeout(2_000) { task.awaitOutcome() }
                assertTrue(outcome is TaskOutcome.Cancelled || outcome is TaskOutcome.Unresolved, "No model result was returned: $outcome")
                assertTrue(task.state.value.isTerminal)
                assertTrue(task.pendingInteractions.value.isEmpty())
            } finally { model.release(); h.close() }
        }
    }

    @Test fun `explicit cancellation waits for native termination and leaves the session reusable`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            model.hold()
            val task = session.startTask(TaskRequest(TaskInput.Text("cancel native request")))
            withTimeout(60_000) { while (model.observedContexts.none { "cancel native request" in it }) delay(10) }
            withTimeout(10_000) { task.requestCancellation() }
            val outcome = withTimeout(10_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Cancelled>(outcome)
            assertEquals(TaskState.CANCELLED, task.state.value)
            assertTrue(task.pendingInteractions.value.isEmpty())
            model.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                session.startTask(TaskRequest(TaskInput.Text("continue after confirmed cancellation"))).awaitOutcome()
            })
        } }
    }
}

/** Selected profiles: diagnostics are supported and schema output is rejected.
 * These are independent expectations for the current three configurations, not mandatory capabilities.
 */
abstract class HarnessRuntimeProfileConformanceTest<B : RuntimeObservation> : HarnessRuntimeConformanceTest<B>() {
    @Test fun `unsupported task requirements are rejected before a native model call`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            val before = model.observedContexts.size
            val request = TaskRequest(TaskInput.Text("structured"), TaskRequirements(OutputRequirement.Structured("{\"type\":\"object\"}")))
            assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(request).status)
            assertFailsWith<IncompatibleRequirementException> { session.startTask(request) }
            assertEquals(before, model.observedContexts.size)
        } }
    }

    @Test fun `independent semantic and diagnostic observers finish and late subscription retains terminal`() = runBlocking<Unit> {
        boundary().use { model -> harness(model).use { h ->
            assertEquals(Support.Supported, h.support[Capability.DIAGNOSTICS])
            model.hold()
            val configured = spec().copy(requirements = SessionRequirements(diagnostics = DiagnosticsRequirement.Required))
            val task = h.createSession(configured).startTask(TaskRequest(TaskInput.Text("observe actual native work")))
            val semantic = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
            val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { (task as TaskDiagnostics).diagnostics.toList() }
            model.release()
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Completed>(outcome)
            val events = withTimeout(2_000) { semantic.await() }
            assertEquals(1, events.filterIsInstance<TaskEvent.Terminal>().size)
            assertIs<TaskEvent.TaskCompleted>(events.last())
            assertTrue(withTimeout(2_000) { diagnostic.await() }.any { it is ProviderDiagnostic })
            val late = withTimeout(2_000) { task.events.toList() }
            assertIs<TaskEvent.TaskCompleted>(late.last())
            // No assertion that terminal must be the first event: past replay is not required.
            task.requestCancellation()
            assertEquals(outcome, task.awaitOutcome())
        } }
    }

}

abstract class HarnessRuntimePersistenceConformanceTest<B : RuntimeObservation> : HarnessRuntimeProfileConformanceTest<B>() {
    protected open val supportsChangedInstructionsOnReopen = true
    @Test fun `persistent reopen preserves actual context and desired instructions across harness recreation`() = runBlocking<Unit> {
        boundary().use { model ->
            val configured = spec().copy(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
            val reference = harness(model).use { h ->
                val session = h.createSession(configured)
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                    session.startTask(TaskRequest(TaskInput.Text("durable marker-delta"))).awaitOutcome()
                })
                val ref = assertNotNull(session.persistentRef)
                session.release()
                ref
            }
            harness(model).use { h ->
                val before = model.observedContexts.size
                val persistent = h as PersistentSessions
                val desired = configured.copy(instructions = "AHP_REOPEN_INSTRUCTION")
                if (!supportsChangedInstructionsOnReopen) {
                    assertFailsWith<IncompatibleRequirementException> { persistent.reopenSession(reference, desired) }
                    assertEquals(before, model.observedContexts.size)
                }
                val reopened = persistent.reopenSession(reference, if (supportsChangedInstructionsOnReopen) desired else configured)
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                    reopened.startTask(TaskRequest(TaskInput.Text("after harness recreation"))).awaitOutcome()
                })
                val actual = model.observedContexts.drop(before).joinToString()
                assertTrue("marker-delta" in actual, "reopen must restore actual native context")
                if (supportsChangedInstructionsOnReopen)
                    assertTrue("AHP_REOPEN_INSTRUCTION" in actual, "desired instructions must reach the resumed model")
                assertFailsWith<IllegalArgumentException> {
                    h.reopenSession(reference.copy(namespace = StorageNamespace("foreign")), configured)
                }
                val unsupported = configured.copy(requirements = configured.requirements.copy(persistence = PersistenceRequirement.Required(acrossProcessRestart = true)))
                assertFailsWith<IncompatibleRequirementException> { h.createSession(unsupported) }
            }
        }
    }
}

