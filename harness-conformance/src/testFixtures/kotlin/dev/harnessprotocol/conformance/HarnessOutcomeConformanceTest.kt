package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

abstract class HarnessOutcomeConformanceTest {
    protected abstract fun outcomeFixture(output: OutputCase): OutcomeFixture
    // Independent expectations for what this native API permits and actually delivers.
    protected open val missingResponseState = TaskState.COMPLETED
    protected open val emptyResponseState = TaskState.COMPLETED
    protected open val emptyTextReachesAdapter = true

    @Test fun `completed runtime preserves captured output`() = partial(TaskState.COMPLETED)
    @Test fun `failed runtime preserves captured partial output`() = partial(TaskState.FAILED)
    @Test fun `cancelled runtime preserves captured partial output`() = partial(TaskState.CANCELLED)
    @Test fun `unresolved runtime preserves captured partial output`() = partial(TaskState.UNRESOLVED)

    private fun partial(expected: TaskState) = runBlocking<Unit> {
        outcomeFixture(OutputCase.PARTIAL).use { f ->
            val session = f.harness.createSession(f.spec)
            val task = session.startTask(TaskRequest(TaskInput.Text("partial-output-evidence")))
            val partialSeen = CompletableDeferred<Unit>()
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                task.events.collect { event ->
                    val text = when (event) { is TaskEvent.MessageDelta -> event.text; is TaskEvent.MessageCompleted -> event.text; else -> "" }
                    if (text.contains("retained-partial")) partialSeen.complete(Unit)
                }
            }
            f.beginModel()
            withTimeout(60_000) { partialSeen.await() }
            when (expected) {
                TaskState.COMPLETED -> f.finishModel()
                TaskState.FAILED -> f.failModel()
                TaskState.CANCELLED -> task.requestCancellation()
                TaskState.UNRESOLVED -> f.loseObservation(session)
                else -> error("unsupported test outcome")
            }
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertEquals(expected, task.state.value, "$outcome")
            val output = assertIs<TaskOutput.Text>(outcome.output)
            assertEquals("retained-partial", output.text)
            if (expected != TaskState.COMPLETED) assertFalse(output.complete)
            assertTrue(task.pendingInteractions.value.isEmpty())
            withTimeout(5_000) { events.await() }
            f.finishModel()
            f.beginModel()
            assertEquals(outcome, task.awaitOutcome())
        }
    }

    @Test fun `response without content preserves native termination and output absence`() = noPartial(OutputCase.MISSING)
    @Test fun `empty model response preserves native output presence`() = noPartial(OutputCase.EMPTY)
    private fun noPartial(mode: OutputCase) = runBlocking<Unit> {
        outcomeFixture(mode).use { f ->
            f.finishModel()
            f.beginModel()
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("output-presence")))
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertEquals(if (mode == OutputCase.MISSING) missingResponseState else emptyResponseState, task.state.value, "$outcome")
            if (mode == OutputCase.MISSING || !emptyTextReachesAdapter) assertNull(outcome.output)
            else assertEquals("", assertIs<TaskOutput.Text>(outcome.output).text)
        }
    }
}
