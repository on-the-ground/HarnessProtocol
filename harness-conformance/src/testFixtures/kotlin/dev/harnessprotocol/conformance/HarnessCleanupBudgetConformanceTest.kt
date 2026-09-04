package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

abstract class HarnessCleanupBudgetConformanceTest {
    protected abstract fun cleanupFixture(): AcceptanceFixture
    private fun request(value: String) = TaskRequest(TaskInput.Text(value))

    @Test fun `close settles several active native sessions within one advertised total budget`() = runBlocking<Unit> {
        cleanupFixture().use { f ->
            f.observation.hold()
            val sessions = List(4) { f.harness.createSession(f.spec) }
            val tasks = sessions.mapIndexed { index, session -> session.startTask(request("cleanup-resource-$index")) }
            withTimeout(60_000) { while (tasks.indices.any { index -> f.observation.observedContexts.none { "cleanup-resource-$index" in it } }) delay(10) }
            val elapsed = measureTime { withContext(Dispatchers.IO) { f.harness.close() } }
            // Runtime scheduling/process reaping have a small measurement tolerance, not a per-resource allowance.
            assertTrue(elapsed <= f.harness.cleanupBudget.total + 500.milliseconds, "close=$elapsed, advertised=${f.harness.cleanupBudget}")
            val outcomes = tasks.map { withTimeout(2_000) { it.awaitOutcome() } }
            assertTrue(outcomes.all { it is TaskOutcome.Cancelled || it is TaskOutcome.Unresolved })
            tasks.forEach { task ->
                assertTrue(task.state.value.isTerminal)
                assertTrue(task.pendingInteractions.value.isEmpty())
                val late = withTimeout(2_000) { task.events.toList() }
                assertEquals(1, late.filterIsInstance<TaskEvent.Terminal>().size)
                assertIs<TaskEvent.Terminal>(late.last())
            }
            sessions.forEach { session -> assertFailsWith<SessionBlockedException> { session.startTask(request("closed-start")) } }
            f.observation.release()
            f.harness.close()
            assertEquals(outcomes, tasks.map { it.awaitOutcome() })
        }
    }

    @Test fun `cancelling the release caller still settles its native work and closes the handle`() = runBlocking<Unit> {
        cleanupFixture().use { f ->
            f.observation.hold()
            val session = f.harness.createSession(f.spec)
            val task = session.startTask(request("cancel-release-caller"))
            withTimeout(60_000) { while (f.observation.observedContexts.none { "cancel-release-caller" in it }) delay(10) }
            val elapsed = measureTime {
                val release = launch(start = CoroutineStart.UNDISPATCHED) { session.release() }
                release.cancelAndJoin()
            }
            assertTrue(elapsed <= f.harness.cleanupBudget.total + 500.milliseconds, "release=$elapsed")
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Cancelled || outcome is TaskOutcome.Unresolved)
            assertFailsWith<SessionBlockedException> { session.startTask(request("after-release")) }
            session.release()
            assertEquals(outcome, task.awaitOutcome())
        }
    }

    @Test fun `cleanup preserves already completed outcomes alongside active work`() = runBlocking<Unit> {
        cleanupFixture().use { f ->
            val done = f.harness.createSession(f.spec).startTask(request("completed-before-close"))
            val original = assertIs<TaskOutcome.Completed>(withTimeout(60_000) { done.awaitOutcome() })
            f.observation.hold()
            val held = f.harness.createSession(f.spec).startTask(request("active-during-close"))
            withTimeout(60_000) { while (f.observation.observedContexts.none { "active-during-close" in it }) delay(10) }
            withContext(Dispatchers.IO) { f.harness.close() }
            withTimeout(2_000) { held.awaitOutcome() }
            assertEquals(original, done.awaitOutcome())
            assertEquals(TaskState.COMPLETED, done.state.value)
        }
    }
}
