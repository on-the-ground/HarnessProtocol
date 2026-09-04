package dev.harnessprotocol.legacy

/**
 * Immutable result of checking whether an adapter can preserve an [AgentSpec].
 *
 * A report remains compatible when it contains warnings only. Call
 * [requireCompatible] at a boundary where incompatible configuration should be
 * converted into an exception.
 *
 * @property issues all discovered compatibility issues, including warnings
 */
data class CompatibilityReport(
    val issues: List<CompatibilityIssue> = emptyList(),
) {
    /** `true` when [issues] contains no [CompatibilitySeverity.ERROR]. */
    val isCompatible: Boolean get() = issues.none { it.severity == CompatibilitySeverity.ERROR }

    /**
     * Returns normally when [isCompatible], otherwise throws with all [issues].
     *
     * @throws IncompatibleAgentSpecException when the report contains an error
     */
    fun requireCompatible() {
        if (!isCompatible) {
            throw IncompatibleAgentSpecException(issues)
        }
    }

    companion object {
        /** Shared empty report for specifications that can be preserved exactly. */
        val Compatible = CompatibilityReport()
    }
}

/**
 * One adapter compatibility diagnostic.
 *
 * @property path dot-separated path into [AgentSpec], such as
 * `executionPolicy.network`
 * @property message human-readable explanation of the semantic mismatch
 * @property severity whether the issue is advisory or prevents execution
 */
data class CompatibilityIssue(
    val path: String,
    val message: String,
    val severity: CompatibilitySeverity = CompatibilitySeverity.ERROR,
)

/** Severity of a [CompatibilityIssue]. */
enum class CompatibilitySeverity {
    /** Advisory information that does not prevent session creation. */
    WARNING,

    /** Semantic incompatibility that requires session creation to fail. */
    ERROR,
}

/**
 * Thrown when an adapter cannot preserve an [AgentSpec].
 *
 * @property issues complete diagnostics used to reject the specification
 */
class IncompatibleAgentSpecException(
    val issues: List<CompatibilityIssue>,
) : IllegalArgumentException(
    issues.joinToString(
        prefix = "The agent specification cannot be preserved: ",
        separator = "; ",
    ) { "${it.path}: ${it.message}" },
)

/**
 * Failure to start, communicate with, or receive a valid response from a
 * provider SDK/runtime boundary.
 */
class HarnessTransportException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Terminal failure or cancellation of an [AgentExecution].
 *
 * It is thrown by [AgentExecution.awaitResult] after a handle has been created;
 * failures before that point are normally [HarnessTransportException]. Catch the
 * subclasses to tell cancellation from failure without re-reading state.
 */
sealed class AgentExecutionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The execution ended in [ExecutionState.FAILED].
 *
 * @property kind portable classification for retry and escalation decisions
 */
class AgentExecutionFailedException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : AgentExecutionException(message, cause)

/** The execution ended in [ExecutionState.CANCELLED]. */
class AgentExecutionCancelledException(
    message: String = "Agent execution was cancelled",
) : AgentExecutionException(message)
