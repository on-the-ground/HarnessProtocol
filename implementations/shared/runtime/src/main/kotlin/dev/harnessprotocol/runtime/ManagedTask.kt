package dev.harnessprotocol.runtime

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional adapter implementation shared by the process and in-process providers.
 * Only native evidence may call completed/failed/cancelled. Observation loss and
 * cleanup expiry use unresolved and never manufacture cancellation confirmation.
 */
class ManagedTask(
    override val id: TaskId,
    override val sessionId: SessionId,
    private val scope: CoroutineScope,
    private val cancelNative: suspend () -> Unit,
    private val respondNative: suspend (InteractionId, InteractionResponse) -> Unit,
    private val onTerminal: (TaskOutcome) -> Unit = {},
) : AgentTask, TaskDiagnostics {
    private val lock = Any()
    private val currentState = MutableStateFlow(TaskState.STARTING)
    private val pending = MutableStateFlow<List<InteractionRequest>>(emptyList())
    private val completion = CompletableDeferred<TaskOutcome>()
    private val observation = Observation<TaskEvent>({ TaskEvent.ObservationGap(id, it) })
    private val diagnostic = Observation<DiagnosticEvent>({ DiagnosticGap(id, it) })
    private val responding = mutableSetOf<InteractionId>()
    private var cancellation: Deferred<Unit>? = null
    private var output: TaskOutput? = null
    private var usage = AgentUsage.Unknown
    private var sessionUsage: AgentUsage? = null
    private var terminal: TaskOutcome? = null

    override val state = currentState.asStateFlow()
    override val pendingInteractions = pending.asStateFlow()
    override val events = observation.flow
    override val diagnostics = diagnostic.flow
    val isTerminal: Boolean get() = synchronized(lock) { terminal != null }

    fun event(event: TaskEvent) = synchronized(lock) {
        require(event.taskId == id)
        if (terminal != null) return@synchronized
        require(event !is TaskEvent.Terminal) { "Use the native settlement methods" }
        when (event) {
            is TaskEvent.TaskStarted -> if (currentState.value == TaskState.STARTING) currentState.value = TaskState.RUNNING
            is TaskEvent.InteractionRequested -> {
                if (pending.value.any { it.interactionId == event.request.interactionId }) return@synchronized
                pending.value += event.request
                currentState.value = TaskState.AWAITING_RESPONSE
            }
            is TaskEvent.InteractionResolved -> {
                if (pending.value.none { it.interactionId == event.interactionId }) return@synchronized
                pending.value = pending.value.filterNot { it.interactionId == event.interactionId }
                if (pending.value.isEmpty()) currentState.value = TaskState.RUNNING
            }
            is TaskEvent.UsageChanged -> { usage = event.task; sessionUsage = event.session }
            else -> Unit
        }
        observation.publish(event)
    }

    fun diagnostic(event: DiagnosticEvent) = synchronized(lock) {
        if (terminal == null) diagnostic.publish(event)
    }

    fun capture(value: TaskOutput) = synchronized(lock) { if (terminal == null) output = value }

    fun completed(reason: StopReason = StopReason.FINISHED) = settle {
        TaskOutcome.Completed(id, output, reason, usage, sessionUsage)
    }
    fun failed(kind: FailureKind, message: String, cause: Throwable? = null) = settle {
        TaskOutcome.Failed(id, kind, message, cause, output, usage, sessionUsage)
    }
    fun cancelled() = settle { TaskOutcome.Cancelled(id, output, usage, sessionUsage) }
    fun unresolved(reason: UnresolvedReason, known: String) = settle {
        TaskOutcome.Unresolved(id, reason, known, output, usage, sessionUsage)
    }

    private fun settle(build: () -> TaskOutcome) = synchronized(lock) {
        if (terminal != null) return@synchronized
        clearPending(ClearReason.TASK_ENDED)
        val outcome = build()
        terminal = outcome
        currentState.value = when (outcome) {
            is TaskOutcome.Completed -> TaskState.COMPLETED
            is TaskOutcome.Failed -> TaskState.FAILED
            is TaskOutcome.Cancelled -> TaskState.CANCELLED
            is TaskOutcome.Unresolved -> TaskState.UNRESOLVED
        }
        // Install the session block before a waiter can start another task.
        onTerminal(outcome)
        val event = when (outcome) {
            is TaskOutcome.Completed -> TaskEvent.TaskCompleted(id, outcome)
            is TaskOutcome.Failed -> TaskEvent.TaskFailed(id, outcome)
            is TaskOutcome.Cancelled -> TaskEvent.TaskCancelled(id, outcome)
            is TaskOutcome.Unresolved -> TaskEvent.TaskUnresolved(id, outcome)
        }
        observation.finish(event)
        diagnostic.finish()
        completion.complete(outcome)
    }

    private fun clearPending(reason: ClearReason) {
        val open = pending.value
        pending.value = emptyList()
        open.forEach { observation.publish(TaskEvent.InteractionResolved(id, it.interactionId, InteractionResolution.Cleared(reason))) }
        if (currentState.value == TaskState.AWAITING_RESPONSE) currentState.value = TaskState.RUNNING
    }

    override suspend fun respond(interactionId: InteractionId, response: InteractionResponse) {
        val operation = synchronized(lock) {
            val request = pending.value.firstOrNull { it.interactionId == interactionId }
                ?: throw IllegalStateException("Unknown or closed interaction: ${interactionId.value}")
            check(interactionId !in responding) { "A response is already in flight" }
            when (request) {
                is InteractionRequest.Approval -> {
                    require(response is InteractionResponse.Approval)
                    require(response.decision in request.availableDecisions)
                }
                is InteractionRequest.Question -> {
                    require(response is InteractionResponse.Answer)
                    require(request.allowsFreeForm || response.text in request.choices)
                }
            }
            responding += interactionId
            // A cancelled caller must not remove the native request's single-response gate.
            scope.async {
                try {
                    respondNative(interactionId, response)
                    event(TaskEvent.InteractionResolved(id, interactionId, InteractionResolution.Responded(response)))
                } catch (failure: Throwable) {
                    if (failure is HarnessTransportException) {
                        synchronized(lock) { responding -= interactionId }
                        throw failure
                    }
                    event(TaskEvent.InteractionResolved(id, interactionId, InteractionResolution.Cleared(ClearReason.RESPONSE_UNCONFIRMED)))
                    throw InteractionResponseUnconfirmedException(UnconfirmedResponse(id, interactionId), "Response acceptance is unconfirmed", failure)
                }
            }
        }
        operation.await()
    }

    override suspend fun requestCancellation() {
        val operation = synchronized(lock) {
            if (terminal != null) return
            cancellation ?: run {
                clearPending(ClearReason.CANCELLATION_REQUESTED)
                scope.async { cancelNative() }.also { cancellation = it }
            }
        }
        operation.await()
    }

    override suspend fun awaitOutcome(): TaskOutcome = completion.await()
}
