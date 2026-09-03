package dev.harnessprotocol.bridge

import dev.harnessprotocol.AgentEvent
import dev.harnessprotocol.AgentExecution
import dev.harnessprotocol.AgentExecutionCancelledException
import dev.harnessprotocol.AgentExecutionFailedException
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.InteractionId
import dev.harnessprotocol.InteractionRequest
import dev.harnessprotocol.InteractionResolution
import dev.harnessprotocol.InteractionResponse
import dev.harnessprotocol.ApprovalDecision
import dev.harnessprotocol.ClearReason
import dev.harnessprotocol.AgentResult
import dev.harnessprotocol.ExecutionId
import dev.harnessprotocol.ExecutionState
import dev.harnessprotocol.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared execution lifecycle used by both SDK adapters.
 *
 * Three responsibilities are kept apart:
 *
 * 1. **Ingestion** — [source] is the lossless, ordered raw event stream from the host.
 * 2. **Control plane** — a single actor maps raw events and updates [state],
 *    [awaitResult] and the terminal outcome. It never waits on a consumer.
 * 3. **Delivery** — every collector of [events] gets its own bounded queue.
 *    Overflow is reported as [AgentEvent.ObservationGap]; the terminal event
 *    is always delivered last.
 *
 * Exactly one terminal outcome is settled, whichever arrives first: a provider
 * terminal event, an end of the raw stream, a transport failure, or a
 * [settleCancelled]/[settleFailed] call from the owning harness.
 */
class BridgeAgentExecution(
    override val id: ExecutionId,
    override val sessionId: SessionId,
    source: Flow<JsonObject>,
    private val bridge: SdkBridge,
    private val scope: CoroutineScope,
    private val mapEvent: (JsonObject) -> List<AgentEvent>,
    private val subscriberCapacity: Int = DEFAULT_SUBSCRIBER_CAPACITY,
    private val onTerminal: (BridgeAgentExecution) -> Unit = {},
) : AgentExecution {
    private val mutableState = MutableStateFlow(ExecutionState.STARTING)
    private val mutablePending = MutableStateFlow<List<InteractionRequest>>(emptyList())
    private val completion = CompletableDeferred<AgentResult>()
    private val terminalEvent = AtomicReference<AgentEvent?>(null)
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val mutableCollectorCount = MutableStateFlow(0)

    /** Number of active [events] collectors. Diagnostic; lets tests wait for a subscription. */
    val collectorCount: StateFlow<Int> = mutableCollectorCount.asStateFlow()

    override val state: StateFlow<ExecutionState> = mutableState.asStateFlow()
    override val pendingInteractions: StateFlow<List<InteractionRequest>> = mutablePending.asStateFlow()

    /** Whether a terminal outcome has been settled. */
    val isTerminal: Boolean get() = terminalEvent.get() != null

    override val events: Flow<AgentEvent> = flow {
        val subscriber = Subscriber(subscriberCapacity)
        // Registration and the terminal check are ordered by the terminal CAS in
        // settle(): a subscriber registered after settle sees the terminal here.
        subscribers += subscriber
        mutableCollectorCount.value = subscribers.size
        terminalEvent.get()?.let { subscriber.finish(it) }
        try {
            for (event in subscriber.channel) emit(event)
            subscriber.tail().forEach { emit(it) }
        } finally {
            subscribers -= subscriber
            mutableCollectorCount.value = subscribers.size
        }
    }

    init {
        scope.launch {
            try {
                source.collect { raw ->
                    if (isTerminal) return@collect
                    val (terminals, others) = mapEvent(raw).partition { it.isTerminal() }
                    others.forEach { deliver(it, terminal = false) }
                    terminals.firstOrNull()?.let { settle(it) }
                }
                if (!isTerminal) {
                    settle(AgentEvent.ExecutionFailed(id, FailureKind.TRANSPORT, "SDK event stream ended before a terminal event"))
                }
            } catch (failure: Throwable) {
                if (isTerminal) return@launch
                settle(AgentEvent.ExecutionFailed(id, FailureKind.TRANSPORT, failure.message ?: "SDK event stream failed"), cause = failure)
            }
        }
    }

    private fun deliver(event: AgentEvent, terminal: Boolean) {
        if (!terminal) updateControlState(event)
        subscribers.forEach { if (terminal) it.finish(event) else it.offer(event) }
    }

    /** Control-plane transitions driven by non-terminal events; runs on the actor only. */
    private fun updateControlState(event: AgentEvent) {
        when (event) {
            is AgentEvent.ExecutionStarted ->
                mutableState.compareAndSet(ExecutionState.STARTING, ExecutionState.RUNNING)

            is AgentEvent.InteractionRequested -> {
                mutablePending.value = mutablePending.value.filter { it.interactionId != event.request.interactionId } + event.request
                if (state.value == ExecutionState.STARTING || state.value == ExecutionState.RUNNING) {
                    mutableState.value = ExecutionState.WAITING
                }
            }

            is AgentEvent.InteractionResolved -> {
                mutablePending.value = mutablePending.value.filter { it.interactionId != event.interactionId }
                if (mutablePending.value.isEmpty()) {
                    mutableState.compareAndSet(ExecutionState.WAITING, ExecutionState.RUNNING)
                }
            }

            else -> Unit
        }
    }

    /**
     * Settles the terminal outcome exactly once. Later calls are ignored.
     * State and completion are updated before any delivery, so no consumer can
     * observe the terminal event ahead of [state].
     */
    private fun settle(event: AgentEvent, cause: Throwable? = null): Boolean {
        if (!terminalEvent.compareAndSet(null, event)) return false
        // Anything still open is cleared; the host reports its own clears too, but a
        // terminal settled locally (close, transport death) must not leave a snapshot.
        val open = mutablePending.value
        mutablePending.value = emptyList()
        open.forEach { request ->
            deliver(AgentEvent.InteractionResolved(id, request.interactionId, InteractionResolution.Cleared(ClearReason.TURN_INTERRUPTED)), terminal = false)
        }
        when (event) {
            is AgentEvent.ExecutionCompleted -> {
                mutableState.value = ExecutionState.COMPLETED
                completion.complete(event.result)
            }
            is AgentEvent.ExecutionCancelled -> {
                mutableState.value = ExecutionState.CANCELLED
                completion.completeExceptionally(AgentExecutionCancelledException())
            }
            is AgentEvent.ExecutionFailed -> {
                mutableState.value = ExecutionState.FAILED
                completion.completeExceptionally(AgentExecutionFailedException(event.kind, event.message, cause))
            }
            else -> error("not a terminal event: $event")
        }
        deliver(event, terminal = true)
        bridge.release(id.value)
        onTerminal(this)
        return true
    }

    /** Owning harness is closing: settle as cancelled unless already terminal. */
    fun settleCancelled(): Boolean = settle(AgentEvent.ExecutionCancelled(id))

    /** Host process died or transport broke: settle as failed unless already terminal. */
    fun settleFailed(message: String, cause: Throwable? = null): Boolean =
        settle(AgentEvent.ExecutionFailed(id, FailureKind.TRANSPORT, message), cause)

    override suspend fun respond(interactionId: InteractionId, response: InteractionResponse) {
        val request = mutablePending.value.firstOrNull { it.interactionId == interactionId }
            ?: throw IllegalStateException("interaction ${interactionId.value} is unknown or already closed")
        val decision = when (request) {
            is InteractionRequest.Approval -> {
                require(response is InteractionResponse.Approval) { "an approval request needs an approval response" }
                require(response.decision in request.availableDecisions) {
                    "decision ${response.decision} is not offered by this request; available: ${request.availableDecisions}"
                }
                response.decision.wire()
            }
        }
        bridge.request(
            method = "respond_interaction",
            params = buildJsonObject {
                put("executionId", id.value)
                put("interactionId", interactionId.value)
                put("response", buildJsonObject { put("decision", decision) })
            },
        )
    }

    override suspend fun cancel() {
        if (isTerminal) return
        bridge.request(
            method = "cancel_execution",
            params = buildJsonObject { put("executionId", id.value) },
        )
    }

    override suspend fun awaitResult(): AgentResult = completion.await()

    private fun AgentEvent.isTerminal(): Boolean =
        this is AgentEvent.ExecutionCompleted ||
            this is AgentEvent.ExecutionFailed ||
            this is AgentEvent.ExecutionCancelled

    /**
     * One collector's bounded queue. Only the actor calls [offer]/[finish]; the
     * collector drains [channel] and then [tail].
     */
    private inner class Subscriber(capacity: Int) {
        val channel = Channel<AgentEvent>(capacity)
        private var dropped = 0L
        @Volatile
        private var terminal: AgentEvent? = null

        fun offer(event: AgentEvent) {
            if (dropped > 0) {
                if (channel.trySend(AgentEvent.ObservationGap(id, dropped)).isSuccess) dropped = 0 else { dropped++; return }
            }
            if (channel.trySend(event).isFailure) dropped++
        }

        fun finish(event: AgentEvent) {
            terminal = event
            channel.close()
        }

        fun tail(): List<AgentEvent> = buildList {
            if (dropped > 0) add(AgentEvent.ObservationGap(id, dropped))
            terminal?.let { add(it) }
        }
    }

    companion object {
        /** Events buffered per collector before [AgentEvent.ObservationGap] is reported. */
        const val DEFAULT_SUBSCRIBER_CAPACITY = 256

        /** Bridge-protocol name of a decision (the host maps it to the provider's wire value). */
        fun ApprovalDecision.wire(): String = when (this) {
            ApprovalDecision.APPROVE_ONCE -> "approve_once"
            ApprovalDecision.APPROVE_FOR_SESSION -> "approve_for_session"
            ApprovalDecision.DECLINE -> "decline"
            ApprovalDecision.CANCEL -> "cancel"
        }
    }
}
