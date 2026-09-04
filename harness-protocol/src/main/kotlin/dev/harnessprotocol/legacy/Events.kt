package dev.harnessprotocol.legacy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Monotonic lifecycle state of one [AgentExecution]. */
enum class ExecutionState {
    /** The provider accepted the request but has not reported active execution yet. */
    STARTING,

    /** The provider reported that the agent loop is active. */
    RUNNING,

    /**
     * One or more [InteractionRequest]s are open; the provider waits for
     * [AgentExecution.respond] or clears them itself. Returns to [RUNNING] when
     * the last open request closes.
     */
    WAITING,

    /** Terminal state indicating that [AgentExecution.awaitResult] returns successfully. */
    COMPLETED,

    /** Terminal state indicating that [AgentExecution.awaitResult] throws for a failure. */
    FAILED,

    /** Terminal state indicating that cancellation ended the execution. */
    CANCELLED,
}

/** Lifecycle status of a named tool operation or observable effect. */
enum class WorkStatus {
    /** The work item was created or began running. */
    STARTED,

    /** Incremental progress or output is available; may occur zero or more times. */
    UPDATED,

    /** The work item finished successfully. */
    COMPLETED,

    /** The work item attempted to run and failed. */
    FAILED,

    /** The work item did not run because an approval or policy declined it. */
    DECLINED,

    /** The work item was stopped before completion because the execution was cancelled. */
    CANCELLED,
}

/** Portable classification of why an execution failed, for retry and escalation decisions. */
enum class FailureKind {
    /** A retry may succeed: rate limit, overload, connection loss, provider 5xx. */
    TRANSIENT,

    /** Authentication or authorization failed; retrying is pointless. */
    AUTHENTICATION,

    /** Provider policy, sandbox, or a safety filter blocked the execution. */
    POLICY_BLOCKED,

    /** The conversation context exceeded the model window and could not proceed. */
    CONTEXT_OVERFLOW,

    /** A provider-imposed budget for the session or account was exhausted. */
    BUDGET_EXCEEDED,

    /** The provider received the request but reported a failure of its own. */
    PROVIDER,

    /** The SDK host process or its transport failed. */
    TRANSPORT,

    /** No portable classification could be made. Inspect [AgentEvent.ProviderEventObserved]. */
    UNKNOWN,
}

/** Why a successful execution stopped. Only [FINISHED] means the agent chose to stop. */
enum class StopReason {
    /** The agent completed its work. */
    FINISHED,

    /** A provider turn or step limit was reached; [AgentResult.finalMessage] holds what exists so far. */
    TURN_LIMIT,

    /** The provider detected a repetitive loop and stopped the agent. */
    LOOP_DETECTED,

    /** The provider stopped the loop for another reason without reporting a failure. */
    PROVIDER_STOPPED,
}

/** Portable classification of a [AgentEvent.Warning]. */
enum class WarningKind {
    /** Context is near its limit; compaction or failure may follow. */
    CONTEXT_PRESSURE,

    /** Provider configuration warning, including approval requests declined by adapter policy. */
    CONFIGURATION,

    /** A provider-reported condition the provider intends to recover from on its own. */
    RECOVERABLE,

    OTHER,
}

/** Portable classification of an externally observable agent effect. */
enum class EffectKind {
    /** A shell or process command. */
    COMMAND,

    /** A filesystem mutation or proposed mutation. */
    FILE_CHANGE,

    /** A web search or web retrieval operation. */
    WEB_SEARCH,

    /** Provider context compaction or equivalent context maintenance. */
    CONTEXT_MANAGEMENT,

    /** An effect known to exist but not covered by another portable category. */
    OTHER,
}

/** Semantic role of an agent message in the execution result. */
enum class MessagePhase {
    /** Interim commentary or progress that is not the final answer. */
    PROGRESS,

    /** User-facing final-answer content. */
    FINAL,
}

/**
 * Provider-neutral observation emitted while one [AgentExecution] runs.
 *
 * Events delivered by a single execution are ordered as observed by its adapter.
 * The stream is live and is not a durable history API. Exactly one of
 * [ExecutionCompleted], [ExecutionFailed], or [ExecutionCancelled] defines the
 * semantic terminal outcome of a conforming execution, but callers should use
 * [AgentExecution.state] or [AgentExecution.awaitResult] as the authoritative
 * completion mechanism because a late event collector may miss earlier events.
 */
sealed interface AgentEvent {
    /** Execution to which this event belongs. */
    val executionId: ExecutionId

    /**
     * Indicates that the provider has begun the agent loop.
     *
     * It corresponds to [ExecutionState.RUNNING], but consumers must not assume
     * that observing the state and event is atomic.
     */
    data class ExecutionStarted(
        override val executionId: ExecutionId,
    ) : AgentEvent

    /**
     * Incremental agent-authored message text.
     *
     * @property text exact fragment to append for this message stream; it may be empty
     * @property phase whether the fragment is interim progress or final-answer content
     */
    data class MessageDelta(
        override val executionId: ExecutionId,
        val text: String,
        val phase: MessagePhase = MessagePhase.PROGRESS,
    ) : AgentEvent

    /**
     * Canonical completed text for one agent-authored message.
     *
     * This is a complete message snapshot rather than another append-only delta.
     * An execution may produce multiple completed progress messages. Use
     * [AgentResult.finalMessage] as the canonical final answer for the execution.
     *
     * @property text complete message text, possibly empty
     * @property phase whether the completed message is progress or a final answer
     */
    data class MessageCompleted(
        override val executionId: ExecutionId,
        val text: String,
        val phase: MessagePhase = MessagePhase.FINAL,
    ) : AgentEvent

    /**
     * Incremental provider-exposed reasoning text or reasoning summary.
     *
     * Providers are not required to expose hidden chain-of-thought. The text must
     * be treated as diagnostic progress rather than the final answer.
     *
     * @property text exact reasoning fragment exposed by the provider
     */
    data class ReasoningDelta(
        override val executionId: ExecutionId,
        val text: String,
    ) : AgentEvent

    /**
     * Lifecycle observation for one named provider tool call.
     *
     * A started item may have zero or more updates and at most one work-terminal
     * status. Execution cancellation or transport failure may end observation
     * before a work-terminal event is available.
     *
     * @property workId identity correlating lifecycle events for this call
     * @property name provider tool name; portable logic should prefer [EffectChanged]
     * when an effect category is available
     * @property status current lifecycle status
     * @property arguments provider arguments, or [JsonNull] when unavailable
     * @property result provider result, or [JsonNull] before or without a result
     * @property error human-readable failure detail when available
     */
    data class ToolCallChanged(
        override val executionId: ExecutionId,
        val workId: WorkId,
        val name: String,
        val status: WorkStatus,
        val arguments: JsonElement = JsonNull,
        val result: JsonElement = JsonNull,
        val error: String? = null,
    ) : AgentEvent

    /**
     * Lifecycle observation of an external effect relevant across providers.
     *
     * One provider work item may emit both [ToolCallChanged] and this event. When
     * they describe the same work they share [workId]; consumers must not count
     * those as two independent operations.
     *
     * @property workId identity correlating lifecycle observations
     * @property kind portable effect category
     * @property status current lifecycle status
     * @property description concise command, query, or provider description
     * @property output incremental or aggregate textual output when available
     * @property exitCode command exit code when the provider exposes one
     * @property changedPaths provider-reported paths affected by a file operation;
     * absence does not prove that no path changed
     */
    data class EffectChanged(
        override val executionId: ExecutionId,
        val workId: WorkId,
        val kind: EffectKind,
        val status: WorkStatus,
        val description: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        val changedPaths: List<String> = emptyList(),
    ) : AgentEvent

    /**
     * Indicates that the provider compacted or otherwise managed session context.
     *
     * @property beforeTokens context size before management, or `null` if unavailable
     * @property afterTokens context size after management, or `null` if unavailable
     */
    data class ContextManaged(
        override val executionId: ExecutionId,
        val beforeTokens: Long? = null,
        val afterTokens: Long? = null,
    ) : AgentEvent

    /**
     * Latest usage snapshot known for this execution.
     *
     * Neither field is a delta to add to the previous event; later snapshots
     * replace earlier ones.
     *
     * @property execution cumulative usage of this execution only
     * @property session cumulative usage of the whole session when the provider reports it
     */
    data class UsageChanged(
        override val executionId: ExecutionId,
        val execution: AgentUsage,
        val session: AgentUsage? = null,
    ) : AgentEvent

    /**
     * Recoverable or advisory condition that does not itself terminate execution.
     *
     * @property kind portable classification
     * @property message human-readable warning text
     */
    data class Warning(
        override val executionId: ExecutionId,
        val kind: WarningKind,
        val message: String,
    ) : AgentEvent

    /**
     * Successful terminal event carrying the same semantic result returned by
     * [AgentExecution.awaitResult].
     *
     * @property result final message and latest available usage
     */
    data class ExecutionCompleted(
        override val executionId: ExecutionId,
        val result: AgentResult,
    ) : AgentEvent

    /**
     * Failed terminal event.
     *
     * [AgentExecution.awaitResult] throws [AgentExecutionFailedException] for
     * this outcome instead of returning an [AgentResult].
     *
     * @property kind portable failure classification
     * @property message human-readable provider or adapter failure description
     */
    data class ExecutionFailed(
        override val executionId: ExecutionId,
        val kind: FailureKind,
        val message: String,
    ) : AgentEvent

    /**
     * Cancelled terminal event.
     *
     * Cancellation may have been requested by the caller, provider, or owning
     * runtime; this event does not encode the initiator.
     */
    data class ExecutionCancelled(
        override val executionId: ExecutionId,
    ) : AgentEvent

    /**
     * The provider asked the caller to decide something and paused the loop.
     * [AgentExecution.pendingInteractions] is the authoritative snapshot; this
     * event may be dropped for a slow collector like any non-terminal event.
     */
    data class InteractionRequested(
        override val executionId: ExecutionId,
        val request: InteractionRequest,
    ) : AgentEvent

    /** An open request closed, by a caller answer or a provider clear. */
    data class InteractionResolved(
        override val executionId: ExecutionId,
        val interactionId: InteractionId,
        val resolution: InteractionResolution,
    ) : AgentEvent

    /**
     * Delivery to this collector fell behind and [droppedEvents] non-terminal
     * events were discarded for it.
     *
     * The gap is per collector: other collectors and [AgentExecution.state],
     * [AgentExecution.awaitResult] are unaffected. Terminal events are never
     * dropped and always follow any gap.
     *
     * @property droppedEvents number of events not delivered to this collector
     */
    data class ObservationGap(
        override val executionId: ExecutionId,
        val droppedEvents: Long,
    ) : AgentEvent

    /**
     * Provider event preserved as an observability escape hatch.
     *
     * "Preserved" means the adapter does not intentionally reduce the event's
     * provider-specific payload when constructing this value; it does not promise
     * durable delivery or replay. Payload schemas may change with provider SDK
     * versions, so portable business logic must not depend on this event.
     *
     * @property provider provider that produced the event
     * @property name provider event method or type
     * @property payload provider-specific event body
     */
    data class ProviderEventObserved(
        override val executionId: ExecutionId,
        val provider: ProviderId,
        val name: String,
        val payload: JsonElement,
    ) : AgentEvent
}

/**
 * Cumulative token usage snapshot for one execution.
 *
 * Every nullable field uses `null` to mean that the provider did not expose a
 * trustworthy value; `0` is a real reported count. [totalTokens] may be a
 * provider-reported total and consumers must not assume that it equals a simple
 * sum of the other fields.
 *
 * @property inputTokens input tokens, including cached tokens when the provider counts them
 * @property cachedInputTokens input tokens served from a provider cache
 * @property outputTokens generated output tokens according to provider accounting
 * @property reasoningTokens reasoning tokens when reported separately
 * @property totalTokens provider-reported total token count
 */
data class AgentUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    /** Field-wise sum where `null + n = n` and `null + null = null`. */
    operator fun plus(other: AgentUsage): AgentUsage = AgentUsage(
        inputTokens = add(inputTokens, other.inputTokens),
        cachedInputTokens = add(cachedInputTokens, other.cachedInputTokens),
        outputTokens = add(outputTokens, other.outputTokens),
        reasoningTokens = add(reasoningTokens, other.reasoningTokens),
        totalTokens = add(totalTokens, other.totalTokens),
    )

    /** Field-wise difference; `null` wherever either side is unknown. */
    operator fun minus(other: AgentUsage): AgentUsage = AgentUsage(
        inputTokens = sub(inputTokens, other.inputTokens),
        cachedInputTokens = sub(cachedInputTokens, other.cachedInputTokens),
        outputTokens = sub(outputTokens, other.outputTokens),
        reasoningTokens = sub(reasoningTokens, other.reasoningTokens),
        totalTokens = sub(totalTokens, other.totalTokens),
    )

    private fun add(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else a + b
    private fun sub(a: Long?, b: Long?): Long? = if (a == null || b == null) null else a - b

    companion object {
        /** A usage snapshot with no known values. */
        val Unknown = AgentUsage()
    }
}

/**
 * Successful terminal value of an [AgentExecution].
 *
 * @property finalMessage canonical user-facing final answer, possibly empty
 * @property stopReason why the execution stopped; only [StopReason.FINISHED] means the agent chose to
 * @property usage cumulative usage of this execution, or `null` when unavailable
 * @property sessionUsage cumulative usage of the session when the provider reports it
 */
data class AgentResult(
    val finalMessage: String,
    val stopReason: StopReason = StopReason.FINISHED,
    val usage: AgentUsage? = null,
    val sessionUsage: AgentUsage? = null,
)
