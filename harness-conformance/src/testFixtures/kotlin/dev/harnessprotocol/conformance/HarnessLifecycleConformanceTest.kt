package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Common Port lifecycle assertions. The fixture controls evidence at the real adapter boundary.
 * Current Codex/Gemini bindings use SDK envelopes; this is not evidence of full native conformance.
 */
abstract class HarnessLifecycleConformanceTest {
    protected abstract fun lifecycleFixture(): LifecycleFixture
    protected open fun compatibleSpec(): SessionSpec = SessionSpec()
    private val terminalStates = TaskState.entries.filter { it.isTerminal }.toSet()

    @Test
    fun `state is terminal before awaitOutcome returns`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            driver.control(execution).reportRunning()
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            assertTrue(execution.state.value in terminalStates)
        }
    }

    @Test
    fun `completes without an event collector`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            driver.control(execution).reportRunning()
            repeat(500) { driver.control(execution).reportMessageDelta("message", "chunk$it ") }
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
        }
    }

    @Test
    fun `slow collector does not block lifecycle`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val gate = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { gate.await() }   // subscribed, but never makes progress
            }
            driver.control(execution).reportRunning()
            repeat(2_000) { driver.control(execution).reportMessageDelta("message", "chunk$it ") }
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
            withTimeout(5_000) { execution.awaitOutcome() }
            assertEquals(TaskState.COMPLETED, execution.state.value)
            gate.complete(Unit)
            collector.cancel()
        }
    }

    @Test
    fun `failure is reported through state and awaitOutcome`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            driver.control(execution).reportRunning()
            driver.control(execution).reportFailure("boom")
            val failure = assertIs<TaskOutcome.Failed>(withTimeout(5_000) { execution.awaitOutcome() })
            assertEquals("boom", failure.message)
            assertEquals(TaskState.FAILED, execution.state.value)
        }
    }

    @Test
    fun `completion wins the race against cancel`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            driver.control(execution).reportRunning()
            execution.requestCancellation()
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
            assertEquals("done", assertIs<TaskOutput.Text>(withTimeout(5_000) { execution.awaitOutcome() }.output).text)
            assertEquals(TaskState.COMPLETED, execution.state.value)
        }
    }

    @Test
    fun `terminal is exactly once and last`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val seen = java.util.concurrent.CopyOnWriteArrayList<TaskEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { execution.events.collect { seen += it } }
            driver.control(execution).reportRunning()
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
            driver.control(execution).reportFailure("late")     // duplicate terminal must be ignored
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
    fun `different sessions execute concurrently`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val a = h.createSession(compatibleSpec())
            val b = h.createSession(compatibleSpec())
            val ea = a.startTask(TaskRequest(TaskInput.Text("a")))
            val eb = b.startTask(TaskRequest(TaskInput.Text("b")))
            driver.control(ea).reportRunning()
            driver.control(eb).reportRunning()
            driver.control(ea).reportCompletion(OutputObservation.Text("A"))
            driver.control(eb).reportCompletion(OutputObservation.Text("B"))
            assertEquals("A", assertIs<TaskOutput.Text>(withTimeout(5_000) { ea.awaitOutcome() }.output).text)
            assertEquals("B", assertIs<TaskOutput.Text>(withTimeout(5_000) { eb.awaitOutcome() }.output).text)
        }
    }

    @Test
    fun `harness close without native termination evidence settles unresolved`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val task = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            driver.control(task).reportRunning()
            h.close()
            assertIs<TaskOutcome.Unresolved>(withTimeout(5_000) { task.awaitOutcome() })
            assertEquals(TaskState.UNRESOLVED, task.state.value)
        }
    }

    @Test
    fun `overflow is explicit and terminal survives`() = runBlocking<Unit> {
        withHarness { driver, h ->
            val execution = h.createSession(compatibleSpec()).startTask(TaskRequest(TaskInput.Text("x")))
            val gate = CompletableDeferred<Unit>()
            val seen = java.util.concurrent.CopyOnWriteArrayList<TaskEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { event ->
                    seen += event
                    if (seen.size == 1) gate.await()   // stall after the first event so the queue overflows
                }
            }
            driver.control(execution).reportRunning()
            val total = 5_000
            repeat(total) { driver.control(execution).reportMessageDelta("message", "chunk$it ") }
            driver.control(execution).reportCompletion(OutputObservation.Text("done"))
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

    protected suspend fun withHarness(block: suspend (LifecycleFixture, AgentHarness) -> Unit) {
        lifecycleFixture().use { block(it, it.harness) }
    }
    protected suspend fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) { while (!condition()) delay(5) }
    }
    private fun TaskEvent.isTerminal() = this is TaskEvent.Terminal
}
