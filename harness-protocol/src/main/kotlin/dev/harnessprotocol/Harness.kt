package dev.harnessprotocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A provider-neutral port for a stateful agent runtime.
 *
 * Implementations translate this contract to a vendor SDK. Vendor classes and
 * wire vocabulary must not escape through this interface. A harness owns the
 * runtime resources and every [AgentSession] and [AgentExecution] created by it.
 * Close the harness when it is no longer needed; handles obtained from a closed
 * harness must not be used.
 *
 * Configuration is semantic rather than best-effort. Call [validate] when the
 * application wants to inspect incompatibilities, and expect [createSession]
 * and [resumeSession] to reject incompatible specifications as well.
 */
interface AgentHarness : AutoCloseable {
    /** Stable identifier of the provider implemented by this harness. */
    val provider: ProviderId

    /**
     * Checks whether this adapter can preserve the requested [spec].
     *
     * Warnings do not make a report incompatible. Errors mean that executing
     * the specification would silently change its meaning and therefore must be
     * rejected. Metadata is opaque and is not treated as a portable behavior.
     */
    fun validate(spec: AgentSpec): CompatibilityReport

    /**
     * Creates a new durable conversation configured by [spec].
     *
     * The returned session retains an immutable snapshot of the specification.
     * This call waits for session creation, not for an agent execution.
     *
     * @throws IncompatibleAgentSpecException if [spec] cannot be preserved
     * @throws HarnessTransportException if the provider runtime cannot create the session
     */
    suspend fun createSession(spec: AgentSpec): AgentSession

    /**
     * Reopens the provider conversation identified by [id] using [spec].
     *
     * A [SessionId] is opaque and provider-scoped. Passing an ID from another
     * provider or an unknown ID fails rather than creating a replacement session.
     *
     * @throws IncompatibleAgentSpecException if [spec] cannot be preserved
     * @throws HarnessTransportException if the provider runtime cannot resume the session
     */
    suspend fun resumeSession(id: SessionId, spec: AgentSpec): AgentSession

    /**
     * Releases the provider runtime and invalidates all handles owned by it.
     * Applications should normally call this through Kotlin's `use` function.
     */
    override fun close() = Unit
}

/**
 * A durable conversation that may contain multiple sequential executions.
 *
 * Each completed execution contributes to the context seen by the next one.
 * Calls to [execute] on the same session must not overlap; an adapter rejects a
 * second execution while the previous one is not terminal. Separate sessions are
 * logically isolated, although an adapter may serialize their underlying
 * provider work.
 *
 * A session's runtime handle lives inside the [AgentHarness] that created it.
 * [release] frees that handle early; the provider conversation itself is durable
 * and can be reopened with [AgentHarness.resumeSession].
 */
interface AgentSession {
    /** Opaque provider conversation identifier, suitable for [AgentHarness.resumeSession]. */
    val id: SessionId

    /** Immutable semantic configuration used when this session was opened. */
    val spec: AgentSpec

    /**
     * Starts one complete agent loop for [input] and returns its live handle.
     *
     * The call returns after the provider accepts the execution. Use
     * [AgentExecution.events] for progress and [AgentExecution.awaitResult] for
     * its terminal outcome.
     *
     * @throws IllegalStateException if a previous execution of this session is
     * not terminal yet, or the session has been released
     * @throws HarnessTransportException if the execution cannot be started
     */
    suspend fun execute(input: AgentInput): AgentExecution

    /**
     * Releases this session's runtime handle inside the harness.
     *
     * An active execution is cancelled first and settled within a bounded wait.
     * The provider's durable conversation is not deleted; [id] stays valid for
     * [AgentHarness.resumeSession]. Idempotent; [execute] after release throws
     * [IllegalStateException].
     */
    suspend fun release()
}

/**
 * A live handle for one agent loop and its observable effects.
 *
 * [state] is the authoritative lifecycle snapshot and [awaitResult] is the
 * authoritative completion API. [events] is an ordered live observation stream,
 * not a durable event store; callers must not assume that a late collector will
 * receive earlier events.
 *
 * A conforming execution moves monotonically from [ExecutionState.STARTING] to
 * [ExecutionState.RUNNING] and then to exactly one terminal state. A provider may
 * terminate before reporting a running state, in which case `STARTING` may move
 * directly to a terminal state.
 */
interface AgentExecution {
    /** Identifier unique within the owning harness runtime. */
    val id: ExecutionId

    /** Session whose durable context contains this execution. */
    val sessionId: SessionId

    /** Hot, observable snapshot of the current lifecycle state. */
    val state: StateFlow<ExecutionState>

    /**
     * Requests currently waiting for [respond]. Empty unless [state] is
     * [ExecutionState.WAITING]. Authoritative, unlike the event stream: a
     * collector that subscribed late still sees what it must answer.
     */
    val pendingInteractions: StateFlow<List<InteractionRequest>>

    /**
     * Ordered observations for this execution.
     *
     * Replay and buffering are implementation details. Consumers should collect
     * promptly and use [state] or [awaitResult] instead of inferring completion
     * from whether a terminal event happened to be observed.
     */
    val events: Flow<AgentEvent>

    /**
     * Answers an open request.
     *
     * Returning means the provider received the answer, not that [state] is
     * back to [ExecutionState.RUNNING]; other requests may still be open.
     *
     * @throws IllegalStateException if [interactionId] is unknown or already closed
     * @throws IllegalArgumentException if [response] does not fit the request
     * (wrong kind, or a decision the request did not offer)
     * @throws HarnessTransportException if the answer cannot be delivered
     */
    suspend fun respond(interactionId: InteractionId, response: InteractionResponse)

    /**
     * Requests cancellation of an active execution.
     *
     * Open interactions are cleared with [ClearReason.TURN_INTERRUPTED] before
     * the provider is interrupted.
     *
     * Returning means that the request was handed to the provider, not that the
     * execution is terminal. Observe [state] or call [awaitResult] to wait. A call
     * made after termination is a no-op; concurrent repeated requests before
     * termination have no additional portable guarantee.
     *
     * @throws HarnessTransportException if the cancellation request cannot be delivered
     */
    suspend fun cancel()

    /**
     * Waits for and returns the successful terminal result.
     *
     * Multiple callers may await the same result. Failure and cancellation are
     * reported as [AgentExecutionException]; provider startup or transport calls
     * that fail before an execution handle exists throw from [AgentSession.execute].
     */
    suspend fun awaitResult(): AgentResult
}
