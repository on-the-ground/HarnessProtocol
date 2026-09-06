package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

/** Native approval effects and deliberately ordered races at the response delivery boundary. */
abstract class HarnessInteractionConformanceTest {
    protected abstract fun interactionFixture(): InteractionRaceFixture
    private val approve = InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)

    @Test fun `wrong response type and unavailable decision never reach native delivery`() = scenario { f, task, request ->
        assertFailsWith<IllegalArgumentException> { task.respond(request.interactionId, InteractionResponse.Answer("yes")) }
        val unavailable = ApprovalDecision.entries.firstOrNull { it !in request.availableDecisions }
        if (unavailable != null) assertFailsWith<IllegalArgumentException> {
            task.respond(request.interactionId, InteractionResponse.Approval(unavailable))
        }
        assertEquals(0, f.response.observedSubmissions())
        assertEquals(0, f.effectCount())
        task.respond(request.interactionId, approve)
        f.observation.release()
        assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
        assertEquals(1, f.effectCount())
    }

    @Test fun `cancelled response caller retains the single submission gate`() = scenario { f, task, request ->
        f.holdResponse()
        val first = launch { task.respond(request.interactionId, approve) }
        withTimeout(5_000) { f.awaitResponseSubmission() }
        first.cancelAndJoin()
        assertFailsWith<IllegalStateException> { task.respond(request.interactionId, approve) }
        assertEquals(1, f.response.observedSubmissions())
        f.releaseResponse()
        f.observation.release()
        assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
        assertEquals(1, f.effectCount())
    }

    @Test fun `cancellation clears pending before returning and prevents an unapproved effect`() = scenario { f, task, request ->
        val seen = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
        task.requestCancellation()
        assertTrue(task.pendingInteractions.value.isEmpty())
        assertFailsWith<IllegalStateException> { task.respond(request.interactionId, approve) }
        val outcome = withTimeout(60_000) { task.awaitOutcome() }
        assertIs<TaskOutcome.Cancelled>(outcome)
        f.observation.release()
        assertEquals(0, f.effectCount())
        assertEquals(0, f.response.observedSubmissions())
        val events = withTimeout(5_000) { seen.await() }
        val resolutions = events.filterIsInstance<TaskEvent.InteractionResolved>().filter { it.interactionId == request.interactionId }
        assertEquals(listOf(InteractionResolution.Cleared(ClearReason.CANCELLATION_REQUESTED)), resolutions.map { it.resolution })
        assertIs<TaskEvent.Terminal>(events.last())
    }

    @Test fun `terminal while response is in flight stays immutable after late delivery`() = scenario { f, task, request ->
        val seen = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
        f.holdResponse()
        val first = async { runCatching { task.respond(request.interactionId, approve) } }
        withTimeout(5_000) { f.awaitResponseSubmission() }
        task.requestCancellation()
        val outcome = withTimeout(60_000) { task.awaitOutcome() }
        assertIs<TaskOutcome.Cancelled>(outcome)
        f.releaseResponse()
        assertIs<InteractionResponseUnconfirmedException>(withTimeout(10_000) { first.await() }.exceptionOrNull())
        f.observation.release()
        assertEquals(outcome, task.awaitOutcome())
        assertTrue(task.pendingInteractions.value.isEmpty())
        assertEquals(0, f.effectCount())
        val events = withTimeout(5_000) { seen.await() }
        assertEquals(1, events.filterIsInstance<TaskEvent.Terminal>().size)
        assertEquals(1, events.filterIsInstance<TaskEvent.InteractionResolved>().count { it.interactionId == request.interactionId })
        assertIs<TaskEvent.Terminal>(events.last())
    }

    @Test fun `completed approval rejects late duplicate responses without repeating the effect`() = scenario { f, task, request ->
        val observed = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
        task.respond(request.interactionId, approve)
        f.observation.release()
        val outcome = withTimeout(60_000) { task.awaitOutcome() }
        assertIs<TaskOutcome.Completed>(outcome)
        assertFailsWith<IllegalStateException> { task.respond(request.interactionId, approve) }
        task.requestCancellation()
        assertEquals(outcome, task.awaitOutcome())
        assertEquals(1, f.response.observedSubmissions())
        assertEquals(1, f.effectCount())
        val effects = withTimeout(5_000) { observed.await() }.filterIsInstance<TaskEvent.EffectChanged>()
            .filter { it.status == WorkStatus.COMPLETED }
        assertEquals(1, effects.size, "The approved native command completed once")
        assertEquals(assertNotNull(request.workId), effects.single().workId,
            "Approval and the observed actual effect must refer to the same work")
    }

    private fun scenario(block: suspend CoroutineScope.(InteractionRaceFixture, AgentTask, InteractionRequest.Approval) -> Unit) = runBlocking<Unit> {
        interactionFixture().use { f ->
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("guarded-effect")))
            withTimeout(60_000) { while (task.pendingInteractions.value.isEmpty()) { check(!task.state.value.isTerminal); delay(10) } }
            block(f, task, assertIs<InteractionRequest.Approval>(task.pendingInteractions.value.single()))
        }
    }
}
