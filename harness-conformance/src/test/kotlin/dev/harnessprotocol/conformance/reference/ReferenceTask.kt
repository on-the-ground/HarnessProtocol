package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.AgentTask
import dev.harnessprotocol.AgentUsage
import dev.harnessprotocol.ClearReason
import dev.harnessprotocol.DiagnosticEvent
import dev.harnessprotocol.DiagnosticGap
import dev.harnessprotocol.EffectKind
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.InteractionId
import dev.harnessprotocol.InteractionRequest
import dev.harnessprotocol.InteractionResolution
import dev.harnessprotocol.InteractionResponse
import dev.harnessprotocol.InteractionResponseUnconfirmedException
import dev.harnessprotocol.MessageId
import dev.harnessprotocol.MessageRole
import dev.harnessprotocol.ProviderDiagnostic
import dev.harnessprotocol.ProviderId
import dev.harnessprotocol.SchemaValidation
import dev.harnessprotocol.SessionId
import dev.harnessprotocol.StopReason
import dev.harnessprotocol.TaskEvent
import dev.harnessprotocol.TaskDiagnostics
import dev.harnessprotocol.TaskId
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskOutput
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.TaskState
import dev.harnessprotocol.UnconfirmedResponse
import dev.harnessprotocol.UnresolvedReason
import dev.harnessprotocol.WorkId
import dev.harnessprotocol.WorkStatus
import dev.harnessprotocol.conformance.OutputObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 참조 엔진의 [AgentTask] 구현.
 *
 * TaskControl(fixture)이 호출하는 모든 진행·종결 연산은 여기 정의한 internal 메서드를 통해서만
 * 상태를 바꾼다. 공개 Port(respond/requestCancellation/awaitOutcome)는 그 결과를 관찰만 한다.
 * 하나의 [mutex]가 모든 상태 전이를 직렬화한다 — 진짜 provider의 동시성 세부사항을 흉내내지
 * 않지만 이 계약이 요구하는 가시적 규칙(terminal 유일성, pending 정리 순서 등)은 그대로 지킨다.
 */
internal class ReferenceTask(
    override val id: TaskId,
    override val sessionId: SessionId,
    val request: TaskRequest,
    private val provider: ProviderId,
    val ownerSession: ReferenceSession,
    val scope: kotlinx.coroutines.CoroutineScope,
    subscriberCapacity: Int = 256,
) : AgentTask, TaskDiagnostics {

    private val mutex = Mutex()
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TaskState.STARTING)
    private val mutablePending = kotlinx.coroutines.flow.MutableStateFlow<List<InteractionRequest>>(emptyList())
    private val completion = CompletableDeferred<TaskOutcome>()
    private val eventBus = Multicast<TaskEvent>(subscriberCapacity) { TaskEvent.ObservationGap(id, it) }
    private val diagBus = Multicast<DiagnosticEvent>(subscriberCapacity) { DiagnosticGap(id, it) }
    private val pendingAnswers = mutableMapOf<String, CompletableDeferred<InteractionResponse>>()
    private val responseModes = mutableMapOf<String, ResponseMode>()
    private val submittedResponses = mutableListOf<InteractionResponse>()
    private val acceptedResponses = mutableListOf<InteractionResponse>()
    private val effectCounts = mutableMapOf<String, Int>()
    private val uncooperativeWork = mutableSetOf<String>()
    private val contextFacts = mutableListOf<String>()

    private var interactionSeq = 0
    private var knownOutput: TaskOutput? = null
    private var taskUsage: AgentUsage = AgentUsage.Unknown
    private var deltaBaseline: AgentUsage = AgentUsage.Zero
    private var sessionUsage: AgentUsage? = null
    private var observedInstructionsValue: String? = null
    private var observedActivatedSkillsValue: Set<String> = emptySet()
    private var cancellationRequested = false

    @Volatile
    var observationLost: Boolean = false
        private set

    @Volatile
    var hasUncooperativeWork: Boolean = false
        private set

    @Volatile
    private var outcomeRef: TaskOutcome? = null

    val isTerminal: Boolean get() = outcomeRef != null
    val outcome: TaskOutcome? get() = outcomeRef

    override val state = mutableState as kotlinx.coroutines.flow.StateFlow<TaskState>
    override val pendingInteractions = mutablePending as kotlinx.coroutines.flow.StateFlow<List<InteractionRequest>>
    override val events = eventBus.flow
    override val diagnostics = diagBus.flow

    // ----------------------------------------------------------- setup (session-driven, pre-fixture)

    fun deliverToRuntime(instructions: String?, activatedSkills: Set<String>) {
        observedInstructionsValue = instructions
        observedActivatedSkillsValue = activatedSkills
    }

    sealed interface ResponseMode {
        data object Accept : ResponseMode
        data class RejectBeforeDelivery(val message: String) : ResponseMode
        data class LoseAcknowledgement(val acceptedByRuntime: Boolean) : ResponseMode
    }

    fun setResponseControl(interactionId: InteractionId, mode: ResponseMode) {
        responseModes[interactionId.value] = mode
    }

    fun observedResponseSubmissions(): Int = submittedResponses.size
    fun observedAcceptedResponses(): List<InteractionResponse> = acceptedResponses.toList()

    // ----------------------------------------------------------------------- AgentTask (public Port)

    override suspend fun respond(interactionId: InteractionId, response: InteractionResponse) {
        var deferredToComplete: CompletableDeferred<InteractionResponse>? = null
        var unconfirmed = false
        var rejectMessage: String? = null
        mutex.withLock {
            val req = mutablePending.value.firstOrNull { it.interactionId == interactionId }
                ?: throw IllegalStateException("interaction ${interactionId.value} is unknown or already closed")
            when (req) {
                is InteractionRequest.Approval -> {
                    require(response is InteractionResponse.Approval) { "approval request needs an approval response" }
                    require(response.decision in req.availableDecisions) {
                        "decision ${response.decision} is not offered by this request"
                    }
                }
                is InteractionRequest.Question -> {
                    require(response is InteractionResponse.Answer) { "question request needs an answer response" }
                }
            }
            val mode = responseModes.remove(interactionId.value) ?: ResponseMode.Accept
            when (mode) {
                is ResponseMode.RejectBeforeDelivery -> {
                    rejectMessage = mode.message
                }
                is ResponseMode.LoseAcknowledgement -> {
                    submittedResponses += response
                    mutablePending.value = mutablePending.value.filterNot { it.interactionId == interactionId }
                    eventBus.offer(TaskEvent.InteractionResolved(id, interactionId, InteractionResolution.Cleared(ClearReason.RESPONSE_UNCONFIRMED)))
                    settleAwaitingIfEmpty()
                    if (mode.acceptedByRuntime) {
                        acceptedResponses += response
                        deferredToComplete = pendingAnswers.remove(interactionId.value)
                    } else {
                        pendingAnswers.remove(interactionId.value)
                    }
                    unconfirmed = true
                }
                ResponseMode.Accept -> {
                    submittedResponses += response
                    acceptedResponses += response
                    mutablePending.value = mutablePending.value.filterNot { it.interactionId == interactionId }
                    eventBus.offer(TaskEvent.InteractionResolved(id, interactionId, InteractionResolution.Responded(response)))
                    settleAwaitingIfEmpty()
                    deferredToComplete = pendingAnswers.remove(interactionId.value)
                }
            }
        }
        rejectMessage?.let { throw HarnessTransportException(it) }
        deferredToComplete?.complete(response)
        if (unconfirmed) {
            throw InteractionResponseUnconfirmedException(UnconfirmedResponse(id, interactionId), "response acceptance acknowledgement lost")
        }
    }

    override suspend fun requestCancellation() {
        mutex.withLock {
            if (isTerminal) return@withLock
            cancellationRequested = true
            val open = mutablePending.value
            mutablePending.value = emptyList()
            open.forEach { req ->
                eventBus.offer(TaskEvent.InteractionResolved(id, req.interactionId, InteractionResolution.Cleared(ClearReason.CANCELLATION_REQUESTED)))
                pendingAnswers.remove(req.interactionId.value)?.completeExceptionally(IllegalStateException("cancellation requested"))
            }
        }
    }

    override suspend fun awaitOutcome(): TaskOutcome = completion.await()

    private fun settleAwaitingIfEmpty() {
        if (mutablePending.value.isEmpty() && mutableState.value == TaskState.AWAITING_RESPONSE) {
            mutableState.value = TaskState.RUNNING
        }
    }

    // ---------------------------------------------------------------------------- TaskControl surface

    suspend fun reportRunning() = mutex.withLock {
        if (isTerminal) return@withLock
        if (mutableState.value == TaskState.STARTING) mutableState.value = TaskState.RUNNING
        eventBus.offer(TaskEvent.TaskStarted(id))
    }

    fun messageId(key: String) = MessageId("${id.value}:msg:$key")
    fun workId(key: String) = WorkId("${id.value}:work:$key")

    suspend fun reportMessageDelta(key: String, text: String, role: MessageRole) = mutex.withLock {
        if (isTerminal) return@withLock
        eventBus.offer(TaskEvent.MessageDelta(id, messageId(key), text, role))
    }

    suspend fun reportMessageCompleted(key: String, text: String, role: MessageRole) = mutex.withLock {
        if (isTerminal) return@withLock
        eventBus.offer(TaskEvent.MessageCompleted(id, messageId(key), text, role))
        contextFacts += text
    }

    fun observedInput() = request.input
    fun observedInstructions() = observedInstructionsValue
    fun observedActivatedSkills() = observedActivatedSkillsValue
    fun observedContextContains(text: String): Boolean = contextFacts.any { it.contains(text) }
    fun addContextFact(text: String) {
        contextFacts += text
    }

    suspend fun reportToolCall(key: String, name: String, effect: EffectKind?): Unit = mutex.withLock {
        if (isTerminal) return@withLock
        val wid = workId(key)
        eventBus.offer(TaskEvent.ToolCallChanged(id, wid, name, WorkStatus.STARTED))
        if (effect != null) eventBus.offer(TaskEvent.EffectChanged(id, wid, effect, WorkStatus.STARTED))
    }

    suspend fun reportToolResult(key: String, result: String?, failed: Boolean) = mutex.withLock {
        if (isTerminal) return@withLock
        eventBus.offer(
            TaskEvent.ToolCallChanged(
                id,
                workId(key),
                "",
                if (failed) WorkStatus.FAILED else WorkStatus.COMPLETED,
                result = result,
            ),
        )
    }

    suspend fun recordEffect(key: String, kind: EffectKind, status: WorkStatus, description: String?) = mutex.withLock {
        if (status == WorkStatus.COMPLETED) effectCounts[key] = (effectCounts[key] ?: 0) + 1
        if (!isTerminal) eventBus.offer(TaskEvent.EffectChanged(id, workId(key), kind, status, description))
    }

    fun observedEffects(key: String): Int = effectCounts[key] ?: 0

    suspend fun openInteraction(req: InteractionRequest): CompletableDeferred<InteractionResponse> {
        val deferred = CompletableDeferred<InteractionResponse>()
        mutex.withLock {
            if (isTerminal) {
                deferred.completeExceptionally(IllegalStateException("task already ended"))
                return@withLock
            }
            pendingAnswers[req.interactionId.value] = deferred
            mutablePending.value = mutablePending.value + req
            if (mutableState.value == TaskState.RUNNING || mutableState.value == TaskState.STARTING) {
                mutableState.value = TaskState.AWAITING_RESPONSE
            }
            eventBus.offer(TaskEvent.InteractionRequested(id, req))
        }
        return deferred
    }

    fun nextInteractionId(): InteractionId {
        interactionSeq++
        return InteractionId("${id.value}:interaction:$interactionSeq")
    }

    suspend fun withdrawOpenRequests() = mutex.withLock {
        val open = mutablePending.value
        mutablePending.value = emptyList()
        open.forEach { req ->
            eventBus.offer(TaskEvent.InteractionResolved(id, req.interactionId, InteractionResolution.Cleared(ClearReason.PROVIDER_WITHDRAWN)))
            pendingAnswers.remove(req.interactionId.value)?.completeExceptionally(IllegalStateException("withdrawn"))
        }
        settleAwaitingIfEmpty()
    }

    suspend fun supersedeRequest(interactionId: InteractionId) = mutex.withLock {
        val req = mutablePending.value.firstOrNull { it.interactionId == interactionId } ?: return@withLock
        mutablePending.value = mutablePending.value - req
        eventBus.offer(TaskEvent.InteractionResolved(id, interactionId, InteractionResolution.Cleared(ClearReason.SUPERSEDED)))
        pendingAnswers.remove(interactionId.value)?.completeExceptionally(IllegalStateException("superseded"))
        settleAwaitingIfEmpty()
    }

    suspend fun reportUsageSnapshot(task: AgentUsage, session: AgentUsage?) = mutex.withLock {
        taskUsage = task
        deltaBaseline = task
        if (session != null) sessionUsage = session
        if (!isTerminal) eventBus.offer(TaskEvent.UsageChanged(id, taskUsage, sessionUsage))
    }

    suspend fun reportUsageDelta(delta: AgentUsage) = mutex.withLock {
        deltaBaseline = deltaBaseline + delta
        taskUsage = deltaBaseline
        if (!isTerminal) eventBus.offer(TaskEvent.UsageChanged(id, taskUsage, sessionUsage))
    }

    suspend fun reportOutput(output: OutputObservation) = mutex.withLock {
        knownOutput = output.toTaskOutput()
    }

    suspend fun reportCompletion(output: OutputObservation?, stopReason: StopReason) {
        val finalOutput = output?.toTaskOutput() ?: knownOutput
        settle {
            TaskOutcome.Completed(id, finalOutput, stopReason, taskUsage, sessionUsage) to { outcome: TaskOutcome.Completed ->
                TaskEvent.TaskCompleted(id, outcome)
            }
        }
    }

    suspend fun reportFailure(message: String, kind: FailureKind?) {
        settle {
            TaskOutcome.Failed(id, kind ?: FailureKind.UNKNOWN, message, null, knownOutput, taskUsage, sessionUsage) to { outcome: TaskOutcome.Failed ->
                TaskEvent.TaskFailed(id, outcome)
            }
        }
    }

    suspend fun reportCancelledTermination() {
        settle {
            TaskOutcome.Cancelled(id, knownOutput, taskUsage, sessionUsage) to { outcome: TaskOutcome.Cancelled ->
                TaskEvent.TaskCancelled(id, outcome)
            }
        }
    }

    suspend fun settleUnresolved(reason: UnresolvedReason, known: String) {
        settle {
            TaskOutcome.Unresolved(id, reason, known, knownOutput, taskUsage, sessionUsage) to { outcome: TaskOutcome.Unresolved ->
                TaskEvent.TaskUnresolved(id, outcome)
            }
        }
    }

    suspend fun endInnerTurnOnly() = mutex.withLock {
        // 내부 turn 종료 신호일 뿐 Task는 계속 진행한다. 상태를 바꾸지 않는다.
        Unit
    }

    suspend fun dropObservationWithoutTerminal() {
        observationLost = true
    }

    suspend fun leaveUncooperativeWork(key: String) = mutex.withLock {
        uncooperativeWork += key
        hasUncooperativeWork = true
    }

    suspend fun releaseUncooperativeWork(key: String) = mutex.withLock {
        uncooperativeWork -= key
        hasUncooperativeWork = uncooperativeWork.isNotEmpty()
    }

    suspend fun reportDiagnostic(name: String, payload: String) {
        if (!isTerminal) diagBus.offer(ProviderDiagnostic(id, provider, name, payload))
    }

    // -------------------------------------------------------------------------------------- internals

    @Suppress("UNCHECKED_CAST")
    private suspend fun <O : TaskOutcome> settle(build: () -> Pair<O, (O) -> TaskEvent.Terminal>) {
        var notify = false
        mutex.withLock {
            if (isTerminal) return@withLock
            val open = mutablePending.value
            mutablePending.value = emptyList()
            open.forEach { req ->
                eventBus.offer(TaskEvent.InteractionResolved(id, req.interactionId, InteractionResolution.Cleared(ClearReason.TASK_ENDED)))
                pendingAnswers.remove(req.interactionId.value)?.completeExceptionally(IllegalStateException("task ended"))
            }
            val (outcome, terminalOf) = build()
            outcomeRef = outcome
            mutableState.value = when (outcome) {
                is TaskOutcome.Completed -> TaskState.COMPLETED
                is TaskOutcome.Failed -> TaskState.FAILED
                is TaskOutcome.Cancelled -> TaskState.CANCELLED
                is TaskOutcome.Unresolved -> TaskState.UNRESOLVED
            }
            completion.complete(outcome)
            eventBus.finish(terminalOf(outcome))
            diagBus.close()
            notify = true
        }
        if (notify) Unit
    }
}

private fun OutputObservation.toTaskOutput(): TaskOutput = when (this) {
    is OutputObservation.Text -> TaskOutput.Text(text, complete)
    is OutputObservation.Structured -> TaskOutput.Structured(json, reportedValidation, complete)
}
