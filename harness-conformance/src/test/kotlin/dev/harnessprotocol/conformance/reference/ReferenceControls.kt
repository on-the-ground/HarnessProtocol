package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.AgentUsage
import dev.harnessprotocol.ApprovalDecision
import dev.harnessprotocol.ApprovalRequirement
import dev.harnessprotocol.Capability
import dev.harnessprotocol.EffectKind
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.InteractionId
import dev.harnessprotocol.InteractionRequest
import dev.harnessprotocol.InteractionResponse
import dev.harnessprotocol.MessageRole
import dev.harnessprotocol.PersistentSessionRef
import dev.harnessprotocol.QuestionRequirement
import dev.harnessprotocol.StopReason
import dev.harnessprotocol.TaskInput
import dev.harnessprotocol.WorkStatus
import dev.harnessprotocol.conformance.MessageKind
import dev.harnessprotocol.conformance.OutputObservation
import dev.harnessprotocol.conformance.PermissionScenario
import dev.harnessprotocol.conformance.ResponseControl
import dev.harnessprotocol.conformance.RuntimeControl
import dev.harnessprotocol.conformance.SessionControl
import dev.harnessprotocol.conformance.StartControl
import dev.harnessprotocol.conformance.TaskControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [TaskControl]의 참조 구현. 실제 mutation은 전부 [ReferenceTask]에 위임한다. */
internal class ReferenceTaskControl(
    private val task: ReferenceTask,
    private val session: ReferenceSession,
    private val scope: CoroutineScope,
) : TaskControl {

    override suspend fun reportRunning() = task.reportRunning()

    override suspend fun reportMessageDelta(messageKey: String, text: String, role: MessageKind?) =
        task.reportMessageDelta(messageKey, text, role.toPortRole())

    override suspend fun reportMessageCompleted(messageKey: String, text: String, role: MessageKind?) =
        task.reportMessageCompleted(messageKey, text, role.toPortRole())

    override fun observedInput(): TaskInput = task.observedInput()
    override fun observedInstructions(): String? = task.observedInstructions()
    override fun observedActivatedSkills(): Set<String> = task.observedActivatedSkills()
    override fun observedContextContains(text: String): Boolean = task.observedContextContains(text)

    override suspend fun reportToolCall(workKey: String, name: String, effect: EffectKind?) =
        task.reportToolCall(workKey, name, effect)

    override suspend fun reportToolResult(workKey: String, result: String?, failed: Boolean) =
        task.reportToolResult(workKey, result, failed)

    override suspend fun attemptGuardedEffect(
        workKey: String,
        targetKey: String,
        prompt: String,
        effect: EffectKind,
        decisions: Set<ApprovalDecision>,
        permission: PermissionScenario?,
    ) {
        if (permission != null && session.hasGrant(permission.grant.scopeId, targetKey)) {
            task.recordEffect(workKey, effect, WorkStatus.STARTED, prompt)
            task.recordEffect(workKey, effect, WorkStatus.COMPLETED, prompt)
            return
        }
        when (session.spec.requirements.approval) {
            ApprovalRequirement.DenyAll -> task.recordEffect(workKey, effect, WorkStatus.DECLINED, prompt)
            ApprovalRequirement.CallerDecides -> {
                val interactionId = task.nextInteractionId()
                val request = InteractionRequest.Approval(
                    interactionId,
                    task.workId(workKey),
                    prompt,
                    effect,
                    decisions,
                    permission?.grant,
                )
                val deferred = task.openInteraction(request)
                val response = runCatching { deferred.await() }.getOrNull() as? InteractionResponse.Approval ?: return
                when (response.decision) {
                    ApprovalDecision.APPROVE_ONCE -> {
                        task.recordEffect(workKey, effect, WorkStatus.STARTED, prompt)
                        task.recordEffect(workKey, effect, WorkStatus.COMPLETED, prompt)
                    }
                    ApprovalDecision.APPROVE_FOR_SESSION -> {
                        permission?.let { session.grant(it.grant.scopeId, it.coveredTargets) }
                        task.recordEffect(workKey, effect, WorkStatus.STARTED, prompt)
                        task.recordEffect(workKey, effect, WorkStatus.COMPLETED, prompt)
                    }
                    ApprovalDecision.DECLINE -> task.recordEffect(workKey, effect, WorkStatus.DECLINED, prompt)
                    ApprovalDecision.CANCEL -> {
                        task.recordEffect(workKey, effect, WorkStatus.DECLINED, prompt)
                        task.reportCancelledTermination()
                    }
                }
            }
            ApprovalRequirement.ProviderDefault, ApprovalRequirement.AgentReviewed -> {
                // 참조 harness의 기본 정책: 자동 승인. 실제 provider의 정책을 대표하지 않는다.
                task.recordEffect(workKey, effect, WorkStatus.STARTED, prompt)
                task.recordEffect(workKey, effect, WorkStatus.COMPLETED, prompt)
            }
        }
    }

    override fun observedEffects(workKey: String): Int = task.observedEffects(workKey)

    override suspend fun askQuestion(prompt: String, choices: List<String>, allowsFreeForm: Boolean) {
        check(session.spec.requirements.questions == QuestionRequirement.CallerAnswers) {
            "this profile did not declare question support"
        }
        val interactionId = task.nextInteractionId()
        val request = InteractionRequest.Question(interactionId, null, prompt, choices, allowsFreeForm)
        val deferred = task.openInteraction(request)
        val response = runCatching { deferred.await() }.getOrNull() as? InteractionResponse.Answer ?: return
        task.addContextFact(response.text)
    }

    override suspend fun withdrawOpenRequests() = task.withdrawOpenRequests()
    override suspend fun supersedeRequest(interactionId: InteractionId) = task.supersedeRequest(interactionId)
    override fun controlNextResponse(interactionId: InteractionId): ResponseControl = ReferenceResponseControl(task, interactionId)

    override suspend fun reportUsageSnapshot(task: AgentUsage, session: AgentUsage?) = this.task.reportUsageSnapshot(task, session)
    override suspend fun reportUsageDelta(delta: AgentUsage) = task.reportUsageDelta(delta)
    override suspend fun reportOutput(output: OutputObservation) = task.reportOutput(output)
    override suspend fun reportCompletion(output: OutputObservation?, stopReason: StopReason) = task.reportCompletion(output, stopReason)
    override suspend fun reportFailure(message: String, kind: FailureKind?) = task.reportFailure(message, kind)
    override suspend fun reportCancelledTermination() = task.reportCancelledTermination()
    override suspend fun endInnerTurnOnly() = task.endInnerTurnOnly()
    override suspend fun dropObservationWithoutTerminal() = task.dropObservationWithoutTerminal()
    override suspend fun leaveUncooperativeWork(workKey: String) = task.leaveUncooperativeWork(workKey)
    override suspend fun releaseUncooperativeWork(workKey: String) = task.releaseUncooperativeWork(workKey)

    override suspend fun scheduleCompletionAfter(millis: Long, output: OutputObservation?) {
        scope.launch {
            delay(millis)
            if (!task.isTerminal) task.reportCompletion(output, StopReason.FINISHED)
        }
    }

    override suspend fun reportDiagnostic(name: String, payload: String) = task.reportDiagnostic(name, payload)
}

private fun MessageKind?.toPortRole(): MessageRole = when (this) {
    MessageKind.ANSWER -> MessageRole.ANSWER
    MessageKind.COMMENTARY -> MessageRole.COMMENTARY
    MessageKind.EXPLANATION -> MessageRole.EXPLANATION
    null -> MessageRole.UNKNOWN
}

internal class ReferenceResponseControl(
    private val task: ReferenceTask,
    private val interactionId: InteractionId,
) : ResponseControl {
    override fun accept() = task.setResponseControl(interactionId, ReferenceTask.ResponseMode.Accept)
    override fun rejectBeforeDelivery(message: String) =
        task.setResponseControl(interactionId, ReferenceTask.ResponseMode.RejectBeforeDelivery(message))
    override fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean) =
        task.setResponseControl(interactionId, ReferenceTask.ResponseMode.LoseAcknowledgement(acceptedByRuntime))
    override fun observedSubmissions(): Int = task.observedResponseSubmissions()
    override fun observedAcceptedResponses(): List<InteractionResponse> = task.observedAcceptedResponses()
}

internal class ReferenceStartControl(private val session: ReferenceSession) : StartControl {
    override fun accept() {
        session.nextStartOverride = ReferenceSession.StartMode.Accept
    }
    override fun rejectBeforeDelivery(message: String) {
        session.nextStartOverride = ReferenceSession.StartMode.RejectBeforeDelivery(message)
    }
    override fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean) {
        session.nextStartOverride = ReferenceSession.StartMode.LoseAcknowledgement(acceptedByRuntime)
    }
    override fun observedSubmissions(): Int = session.observedSubmissions()
    override fun observedAcceptedStarts(): Int = session.observedAcceptedStarts()
}

internal class ReferenceSessionControl(private val harness: PersistentReferenceHarness?) : SessionControl {
    override fun failNextPersistenceWrite(message: String) {
        checkNotNull(harness) { "this profile does not declare persistence support" }.failNextPersistenceWrite(message)
    }
    override fun failNextReopen(message: String) {
        checkNotNull(harness) { "this profile does not declare persistence support" }.failNextReopen(message)
    }
    override fun canonicalizeNextReopenAs(ref: PersistentSessionRef) {
        checkNotNull(harness) { "this profile does not declare persistence support" }.canonicalizeNextReopenAs(ref)
    }
}

internal class ReferenceRuntimeControl(private val harness: ReferenceHarness) : RuntimeControl {
    override suspend fun killRuntime(ownsRunningWork: Boolean) {
        harness.sessions.values.forEach { session ->
            val task = session.activeTask() ?: return@forEach
            if (ownsRunningWork) {
                task.reportFailure("reference runtime process died", FailureKind.TRANSPORT)
            } else {
                task.leaveUncooperativeWork("external:${task.id.value}")
            }
        }
    }

    override suspend fun revokeSupport(capability: Capability) = harness.revoke(capability)

    override suspend fun restartProcessBoundary() = harness.markRestarted()
}
