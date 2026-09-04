package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*

abstract class HarnessAcceptedStartConformanceTest {
    protected abstract fun acceptanceFixture(): AcceptanceFixture

    @Test fun `accepted start yields a usable identity while real work is held`() = runBlocking<Unit> {
        acceptanceFixture().use { f ->
            f.observation.hold()
            val session = f.harness.createSession(f.spec)
            val task = withTimeout(60_000) { session.startTask(text("held-accepted-start")) }
            assertEquals(session.id, task.sessionId)
            waitFor { f.observation.observedContexts.any { "held-accepted-start" in it } }
            assertFalse(task.state.value.isTerminal)
            f.observation.release()
            assertEquals(task.id, assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() }).taskId)
        }
    }

    protected fun text(value: String) = TaskRequest(TaskInput.Text(value))
    protected suspend fun waitFor(check: () -> Boolean) = withTimeout(60_000) { while (!check()) delay(10) }
}

abstract class HarnessStartAcceptanceConformanceTest : HarnessAcceptedStartConformanceTest() {
    abstract override fun acceptanceFixture(): StartAcceptanceFixture

    @Test fun `confirmed nondelivery permits retry without a native start`() = runBlocking<Unit> {
        acceptanceFixture().use { f ->
            val session = f.harness.createSession(f.spec)
            f.start.rejectBeforeDelivery("controlled nondelivery")
            assertFailsWith<HarnessTransportException> { session.startTask(text("not-delivered")) }
            assertEquals(1, f.start.observedSubmissions())
            assertEquals(0, f.start.observedAcceptedStarts())
            assertTrue(f.observation.observedContexts.isEmpty())
            f.start.accept()
            val task = session.startTask(text("safe-retry"))
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertEquals(2, f.start.observedSubmissions())
            assertEquals(1, f.start.observedAcceptedStarts())
        }
    }

    @Test fun `lost acknowledgement after native acceptance blocks resubmission`() = lostStart(true)
    @Test fun `lost acknowledgement without native acceptance still blocks resubmission`() = lostStart(false)

    private fun lostStart(accepted: Boolean) = runBlocking<Unit> {
        acceptanceFixture().use { f ->
            f.observation.hold()
            val session = f.harness.createSession(f.spec)
            f.start.loseAcceptanceAcknowledgement(accepted)
            val error = assertFailsWith<TaskStartUnconfirmedException> { session.startTask(text("uncertain-start")) }
            assertEquals(session.id, error.reference.sessionId)
            assertTrue(error.reference.requestId.isNotBlank())
            assertEquals(f.submittedStarts.single(), error.reference, "The uncertainty reference must identify the actual submitted request")
            assertEquals(1, f.start.observedSubmissions())
            assertEquals(if (accepted) 1 else 0, f.start.observedAcceptedStarts())
            assertFailsWith<SessionBlockedException> { session.startTask(text("must-not-resubmit")) }
            assertEquals(1, f.start.observedSubmissions())
            if (accepted) waitFor { f.observation.observedContexts.any { "uncertain-start" in it } }
            else assertTrue(f.observation.observedContexts.isEmpty())
            f.observation.release()
            f.start.accept()
            // Uncertainty is attached to this context, not every session on the harness.
            val independent = f.harness.createSession(f.spec).startTask(text("independent-after-loss"))
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { independent.awaitOutcome() })
            assertFailsWith<SessionBlockedException> { session.startTask(text("late-retry")) }
            assertEquals(2, f.start.observedSubmissions())
        }
    }
}

abstract class HarnessResponseAcceptanceConformanceTest {
    protected abstract fun responseFixture(): ResponseAcceptanceFixture
    private val approval = InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)

    @Test fun `confirmed response nondelivery leaves the native request retryable`() = runBlocking<Unit> {
        responseFixture().use { f ->
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("guarded-effect")))
            val request = pending(task)
            assertEquals(0, f.effectCount())
            f.response.rejectBeforeDelivery("controlled nondelivery")
            assertFailsWith<HarnessTransportException> { task.respond(request.interactionId, approval) }
            assertTrue(task.pendingInteractions.value.any { it.interactionId == request.interactionId })
            assertEquals(emptyList(), f.response.observedAcceptedResponses())
            assertEquals(0, f.effectCount())
            f.response.accept()
            task.respond(request.interactionId, approval)
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertEquals(1, f.effectCount())
            assertEquals(listOf(approval), f.response.observedAcceptedResponses())
            assertEquals(2, f.response.observedSubmissions())
        }
    }

    @Test fun `response acceptance loss after native acceptance prevents duplicate effects`() = lostResponse(true)
    @Test fun `response acceptance loss before native acceptance prevents duplicate submission`() = lostResponse(false)

    private fun lostResponse(accepted: Boolean) = runBlocking<Unit> {
        responseFixture().use { f ->
            val task = f.harness.createSession(f.spec).startTask(TaskRequest(TaskInput.Text("guarded-effect")))
            val events = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
            val request = pending(task)
            assertEquals(0, f.effectCount())
            f.response.loseAcceptanceAcknowledgement(accepted)
            val failure = assertFailsWith<InteractionResponseUnconfirmedException> { task.respond(request.interactionId, approval) }
            assertEquals(UnconfirmedResponse(task.id, request.interactionId), failure.reference)
            assertTrue(task.pendingInteractions.value.none { it.interactionId == request.interactionId })
            assertFailsWith<IllegalStateException> { task.respond(request.interactionId, approval) }
            assertEquals(1, f.response.observedSubmissions())
            assertEquals(if (accepted) listOf(approval) else emptyList(), f.response.observedAcceptedResponses())
            if (accepted) {
                f.observation.release()
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
                assertEquals(1, f.effectCount())
            } else {
                assertEquals(0, f.effectCount())
                assertFalse(task.state.value.isTerminal, "Lost response acknowledgement is not task cancellation")
                task.requestCancellation()
                withTimeout(60_000) { task.awaitOutcome() }
                assertEquals(0, f.effectCount())
            }
            val resolutions = withTimeout(5_000) { events.await() }.filterIsInstance<TaskEvent.InteractionResolved>()
                .filter { it.interactionId == request.interactionId }
            assertEquals(listOf(InteractionResolution.Cleared(ClearReason.RESPONSE_UNCONFIRMED)), resolutions.map { it.resolution })
        }
    }

    private suspend fun pending(task: AgentTask): InteractionRequest.Approval = withTimeout(60_000) {
        while (task.pendingInteractions.value.isEmpty()) { check(!task.state.value.isTerminal); delay(10) }
        assertIs<InteractionRequest.Approval>(task.pendingInteractions.value.single())
    }
}
