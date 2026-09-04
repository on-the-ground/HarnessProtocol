package dev.harnessprotocol.legacy

/**
 * Provider-neutral intent used to configure a durable agent session.
 *
 * An adapter must preserve every field or report an error from
 * [AgentHarness.validate]. It must not silently replace an unsupported policy
 * with a provider default.
 *
 * @property instructions persistent system/developer-level guidance; `null` asks
 * the provider to use its default instructions
 * @property model exact provider model identifier; `null` selects the provider default
 * @property workingDirectory provider-visible working directory; `null` selects
 * the adapter or provider default
 * @property skills skill contexts made available and explicitly activated for
 * every execution in the session
 * @property executionPolicy requested filesystem, network, and approval behavior
 */
data class AgentSpec(
    val instructions: String? = null,
    val model: String? = null,
    val workingDirectory: String? = null,
    val skills: List<SkillReference> = emptyList(),
    val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
)

/**
 * A provider skill to make available and activate for a session's executions.
 *
 * @property name non-blank provider-visible skill name used for activation
 * @property path non-blank filesystem path to the skill directory; absolute paths
 * are recommended because relative-path resolution is adapter-runtime dependent
 */
data class SkillReference(
    val name: String,
    val path: String,
) {
    init {
        require(name.isNotBlank()) { "skill name must not be blank" }
        require(path.isNotBlank()) { "skill path must not be blank" }
    }
}

/**
 * Security and approval intent for executions in a session.
 *
 * @property filesystem filesystem boundary requested for provider tools
 * @property network outbound network intent for provider tools
 * @property approval policy for effects that require provider approval
 */
data class ExecutionPolicy(
    val filesystem: FilesystemAccess = FilesystemAccess.ProviderDefault,
    val network: NetworkAccess = NetworkAccess.PROVIDER_DEFAULT,
    val approval: ApprovalPolicy = ApprovalPolicy.PROVIDER_DEFAULT,
)

/** Filesystem access boundary requested for agent tools and commands. */
sealed interface FilesystemAccess {
    /** Uses the adapter or provider's configured filesystem policy without overriding it. */
    data object ProviderDefault : FilesystemAccess

    /** Allows reads but requests that filesystem mutations be prevented. */
    data object ReadOnly : FilesystemAccess

    /**
     * Allows writes in the session workspace and optional additional roots.
     *
     * @property additionalWritableRoots extra filesystem roots that may be
     * modified in addition to [AgentSpec.workingDirectory]; an empty set limits
     * writes to the workspace according to provider semantics
     */
    data class WorkspaceWrite(
        val additionalWritableRoots: Set<String> = emptySet(),
    ) : FilesystemAccess

    /** Requests execution without a harness-defined filesystem sandbox. */
    data object FullAccess : FilesystemAccess
}

/** Outbound network intent for provider-controlled tools and commands. */
enum class NetworkAccess {
    /** Uses the adapter or provider's configured network policy. */
    PROVIDER_DEFAULT,

    /** Requests that outbound network access be denied. */
    DENIED,

    /**
     * Permits outbound network access but does not guarantee connectivity,
     * credentials, service availability, or authorization.
     */
    ALLOWED,
}

/** Approval behavior requested for provider-controlled external effects. */
enum class ApprovalPolicy {
    /** Uses the adapter or provider's configured approval behavior. */
    PROVIDER_DEFAULT,

    /** Declines effects for which provider policy requires approval. */
    DENY_ALL,

    /** Delegates approval decisions to the provider's automatic agent reviewer. */
    AGENT_REVIEWED,

    /**
     * Surfaces every provider approval request as [AgentEvent.InteractionRequested]
     * and waits for [AgentExecution.respond]. What needs approval remains provider
     * policy; only who answers changes.
     */
    CALLER_DECIDES,
}

/** Provider-neutral input accepted by an agent execution. */
sealed interface AgentInput {
    /**
     * Non-empty user text supplied as the next message in a session.
     *
     * @property text exact user text; whitespace-only text is valid
     * @throws IllegalArgumentException if [text] is empty
     */
    data class Text(val text: String) : AgentInput {
        init {
            require(text.isNotEmpty()) { "input text must not be empty" }
        }
    }
}
