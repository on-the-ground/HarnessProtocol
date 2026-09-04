package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

/** Native model measurements: (10,5,15), (7,unknown,unknown), then measured zero. */
abstract class HarnessAccountingSequenceConformanceTest {
    protected abstract fun sequenceFixture(): AcceptanceFixture

    @Test fun `unmeasured inner segment keeps the task total unknown through subsequent measured zero`() = runBlocking<Unit> {
        sequenceFixture().use { f ->
            f.observation.hold()
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("measure-several-native-calls")))
            val events = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
            f.observation.release()
            val outcome = assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertEquals(17L, outcome.usage.inputTokens)
            assertNull(outcome.usage.outputTokens)
            assertNull(outcome.usage.totalTokens)
            val snapshots = withTimeout(5_000) { events.await() }.filterIsInstance<TaskEvent.UsageChanged>().map { it.task }
            assertEquals(listOf(10L, 17L, 17L), snapshots.map { it.inputTokens })
            assertEquals(listOf(5L, null, null), snapshots.map { it.outputTokens })
            assertEquals(outcome.usage, snapshots.last())
        }
    }
}
