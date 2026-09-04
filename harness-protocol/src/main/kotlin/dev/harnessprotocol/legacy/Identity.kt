package dev.harnessprotocol.legacy

/**
 * Stable, non-blank identifier for an [AgentHarness] implementation.
 *
 * The value is suitable for configuration and adapter selection, for example
 * `codex` or `gemini-cli`. It is not a display name.
 *
 * @property value provider identifier
 * @throws IllegalArgumentException if [value] is blank
 */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "provider id must not be blank" }
    }
}

/**
 * Opaque, non-blank identifier of a durable provider conversation.
 *
 * IDs are scoped to their [ProviderId] and must not be parsed or synthesized by
 * consumers. Persist both provider and session ID when later resume is required.
 *
 * @property value opaque provider session identifier
 * @throws IllegalArgumentException if [value] is blank
 */
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "session id must not be blank" }
    }
}

/**
 * Non-blank identifier of one agent execution.
 *
 * The identifier correlates [AgentExecution], its state, and all [AgentEvent]
 * values. Consumers must treat it as opaque and unique only within the owning
 * harness runtime unless an adapter documents a stronger scope.
 *
 * @property value opaque execution identifier
 * @throws IllegalArgumentException if [value] is blank
 */
@JvmInline
value class ExecutionId(val value: String) {
    init {
        require(value.isNotBlank()) { "execution id must not be blank" }
    }
}

/**
 * Non-blank identifier of a tool operation or observable effect.
 *
 * The ID is scoped to one [ExecutionId]. Events with the same work ID describe
 * the lifecycle of the same provider work item; a tool and its classified effect
 * should share the ID when they represent the same work.
 *
 * @property value opaque provider or adapter work identifier
 * @throws IllegalArgumentException if [value] is blank
 */
@JvmInline
value class WorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "work id must not be blank" }
    }
}
