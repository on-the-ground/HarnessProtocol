package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

abstract class HarnessObservationLoadConformanceTest {
    protected abstract fun loadFixture(): AcceptanceFixture

    @Test fun `stalled semantic and diagnostic readers have independent counted gaps and retained terminal`() = runBlocking<Unit> {
        loadFixture().use { f ->
            f.observation.hold()
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("native-observation-load")))
            withTimeout(60_000) { task.state.first { it == TaskState.RUNNING } }
            val release = CompletableDeferred<Unit>()
            val semantic = CopyOnWriteArrayList<TaskEvent>()
            val diagnostic = CopyOnWriteArrayList<DiagnosticEvent>()
            val fastSemantic = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
            val fastDiagnostic = async(start = CoroutineStart.UNDISPATCHED) { (task as TaskDiagnostics).diagnostics.toList() }
            val slowSemantic = launch(start = CoroutineStart.UNDISPATCHED) {
                task.events.collect { semantic += it; if (semantic.size == 1) release.await() }
            }
            val slowDiagnostic = launch(start = CoroutineStart.UNDISPATCHED) {
                (task as TaskDiagnostics).diagnostics.collect { diagnostic += it; if (diagnostic.size == 1) release.await() }
            }
            f.observation.release()
            val outcome = assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertTrue(assertIs<TaskOutput.Text>(outcome.output).text.endsWith("load-complete"), "Load did not reach its final marker: $outcome")
            val fastEvents = withTimeout(5_000) { fastSemantic.await() }
            val fastDiagnostics = withTimeout(5_000) { fastDiagnostic.await() }
            release.complete(Unit)
            withTimeout(5_000) { slowSemantic.join(); slowDiagnostic.join() }
            assertTrue(semantic.any { it is TaskEvent.ObservationGap })
            assertTrue(diagnostic.any { it is DiagnosticGap })
            fun semanticCount(values: List<TaskEvent>) = values.sumOf {
                when (it) { is TaskEvent.ObservationGap -> it.droppedEvents; is TaskEvent.TaskStarted -> 0L; else -> 1L }
            }
            fun diagnosticCount(values: List<DiagnosticEvent>) = values.sumOf { if (it is DiagnosticGap) it.droppedRecords else 1L }
            assertEquals(semanticCount(fastEvents), semanticCount(semantic), "Each lost semantic event must be counted once")
            assertEquals(diagnosticCount(fastDiagnostics), diagnosticCount(diagnostic), "Each lost diagnostic event must be counted once")
            assertTrue(semanticCount(semantic) > 256)
            assertTrue(diagnosticCount(diagnostic) > 256)
            assertEquals(1, semantic.filterIsInstance<TaskEvent.Terminal>().size)
            assertIs<TaskEvent.Terminal>(semantic.last())
            assertEquals(outcome, task.awaitOutcome())
        }
    }
}
