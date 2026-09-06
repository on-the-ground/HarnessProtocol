package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Applicable to configurations that cannot express an enforceable session grant. */
abstract class HarnessApprovalScopeConformanceTest {
    protected abstract fun approvalFixture(): RepeatedApprovalFixture
    private suspend fun pending(task: AgentTask): InteractionRequest.Approval = withTimeout(60_000) {
        while (task.pendingInteractions.value.isEmpty()) { check(!task.state.value.isTerminal); delay(10) }
        assertIs<InteractionRequest.Approval>(task.pendingInteractions.value.single())
    }
    private fun request() = TaskRequest(TaskInput.Text("guarded-effect"))

    @Test fun `opaque native session approval is not exposed as an unbounded grant`() = runBlocking<Unit> {
        approvalFixture().use { f ->
            val task = f.harness.createSession(f.spec).startTask(request())
            val approval = pending(task)
            assertNull(approval.sessionGrant)
            assertFalse(ApprovalDecision.APPROVE_FOR_SESSION in approval.availableDecisions)
            assertFailsWith<IllegalArgumentException> {
                task.respond(approval.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_FOR_SESSION))
            }
            assertEquals(0, f.response.observedSubmissions())
            assertEquals(0, f.effectCount())
            task.respond(approval.interactionId, InteractionResponse.Approval(ApprovalDecision.DECLINE))
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            assertEquals(0, f.effectCount(), "Declining the effect does not require failing the whole task")
        }
    }

    @Test fun `one shot approval does not authorize the next task on the same context`() = repeatEffect(false)
    @Test fun `one shot approval does not leak into another session`() = repeatEffect(true)
    private fun repeatEffect(newSession: Boolean) = runBlocking<Unit> {
        approvalFixture().use { f ->
            val session = f.harness.createSession(f.spec)
            val first = session.startTask(request())
            first.respond(pending(first).interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { first.awaitOutcome() })
            assertEquals(1, f.effectCount())
            f.prepareNextEffect()
            val second = (if (newSession) f.harness.createSession(f.spec) else session).startTask(request())
            val approval = pending(second)
            assertNotEquals(first.id, second.id)
            assertEquals(1, f.effectCount(), "The second effect must wait for a new approval")
            second.respond(approval.interactionId, InteractionResponse.Approval(ApprovalDecision.DECLINE))
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { second.awaitOutcome() })
            assertEquals(1, f.effectCount())
        }
    }
}
