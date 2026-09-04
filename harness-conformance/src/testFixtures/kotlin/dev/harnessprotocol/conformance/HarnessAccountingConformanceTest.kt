package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

abstract class HarnessAccountingConformanceTest {
    protected abstract fun accountingFixture(): AccountingFixture

    @Test fun `task accounting resets across tasks including measured zero and retains the last snapshot`() = runBlocking<Unit> {
        accountingFixture().use { f ->
            val session = f.harness.createSession(f.spec)
            f.measurements.forEachIndexed { index, expected ->
                f.observation.hold()
                val task = session.startTask(TaskRequest(TaskInput.Text("accounting-$index")))
                val observed = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
                f.observation.release()
                val outcome = assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
                assertMeasured(expected, outcome.usage)
                val expectedSession = f.sessionMeasurements[index]
                if (expectedSession == null) assertNull(outcome.sessionUsage)
                else assertMeasured(expectedSession, assertNotNull(outcome.sessionUsage))
                val events = withTimeout(5_000) { observed.await() }
                val finalUsage = assertNotNull(events.filterIsInstance<TaskEvent.UsageChanged>().lastOrNull())
                assertEquals(outcome.usage, finalUsage.task)
                assertEquals(outcome.sessionUsage, finalUsage.session)
                assertTrue(events.all { it.taskId == task.id })
                val messages = events.filterIsInstance<TaskEvent.MessageCompleted>()
                assertTrue(messages.isNotEmpty())
                assertTrue(messages.all { it.messageId.value.isNotBlank() })
                assertEquals("measured-result", assertIs<TaskOutput.Text>(outcome.output).text)
            }
        }
    }

    @Test fun `cancellation before any measurement preserves unknown instead of zero`() = runBlocking<Unit> {
        accountingFixture().use { f ->
            f.observation.hold()
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("unmeasured-cancellation")))
            withTimeout(60_000) { while (f.observation.observedContexts.none { "unmeasured-cancellation" in it }) delay(10) }
            task.requestCancellation()
            val outcome = assertIs<TaskOutcome.Cancelled>(withTimeout(10_000) { task.awaitOutcome() })
            assertEquals(AgentUsage.Unknown, outcome.usage)
            assertNull(outcome.sessionUsage)
        }
    }

    private fun assertMeasured(expected: AgentUsage, actual: AgentUsage) {
        assertEquals(expected.inputTokens, actual.inputTokens)
        assertEquals(expected.outputTokens, actual.outputTokens)
        assertEquals(expected.totalTokens, actual.totalTokens)
    }
}
