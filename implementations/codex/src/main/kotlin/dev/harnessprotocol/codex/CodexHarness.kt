package dev.harnessprotocol.codex

import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/** Task port backed by the Python CodexClient host and the real Codex App Server. */
open class CodexHarness protected constructor(
    bridge: SdkBridge,
    scope: CoroutineScope,
    namespace: StorageNamespace?,
    private val reasoningEffort: String?,
) : ProcessTaskHarness(bridge, scope, storageNamespace = namespace), ReasoningOptionDiscovery {
    init {
        require(reasoningEffort == null || reasoningEffort.isNotBlank()) {
            "reasoningEffort must be null or non-blank"
        }
    }

    override val provider = ProviderId("codex")
    private val reasoningCatalogs = ConcurrentHashMap<String, ReasoningOptionCatalog>()
    override val support = SupportReport(mapOf(
        Capability.CALLER_APPROVAL to Support.Supported,
        Capability.QUESTIONS to Support.Unsupported("The configured client exposes approval handlers, not a typed question channel"),
        Capability.PERSISTENCE to if (namespace != null) Support.Conditional(SupportScope.SESSION, "Same application process; no concurrent access") else Support.Unsupported("Configure storageNamespace"),
        Capability.CONTEXT_RETENTION to Support.Supported,
        Capability.USER_HISTORY_VISIBILITY to Support.Unknown("Codex ephemeral confirms local conversation materialization, not every account history surface"),
        Capability.WORKSPACE to Support.Supported,
        Capability.EXECUTION_CONSTRAINT to Support.Conditional(SupportScope.SESSION, "Network policy requires workspace-write; restrictive constraints require DenyAll approval"),
        Capability.STRUCTURED_OUTPUT to Support.Unsupported("Schema enforcement is not configured"),
        Capability.DIAGNOSTICS to Support.Supported,
        Capability.REASONING_OPTION_SELECTION to Support.Conditional(
            SupportScope.SESSION,
            "Available options depend on the selected Codex model and are revalidated at task start",
        ),
    ))
    override fun validate(spec: SessionSpec) = CompatibilityReport(buildList {
        addAll(persistenceIssues(spec))
        addAll(workspaceIssues(spec))
        if (spec.requirements.persistence is PersistenceRequirement.Required &&
            spec.requirements.retention == ContextRetentionRequirement.Ephemeral
        ) add(CompatibilityIssue("requirements.retention", "Ephemeral retention cannot provide a persistent session reference"))
        if (spec.requirements.historyVisibility == UserHistoryVisibilityRequirement.Hidden)
            add(CompatibilityIssue(
                "requirements.historyVisibility",
                "The pinned Codex SDK does not independently confirm account history or Recents visibility",
                CompatibilityIssueKind.UNCONFIRMED,
            ))
        if (spec.requirements.questions != QuestionRequirement.NotRequired)
            add(CompatibilityIssue("requirements.questions", "Typed question mediation is not exposed by this client connection"))
        val execution = spec.requirements.execution as? ExecutionConstraint.Required
        if (execution?.network != null && execution.filesystem !is FilesystemAccess.WorkspaceWrite)
            add(CompatibilityIssue("requirements.execution.network", "Codex network policy requires workspace-write; the adapter will not silently change filesystem policy"))
        val restrictive = execution != null &&
            (execution.filesystem != FilesystemAccess.FullAccess || execution.network == NetworkAccess.DENIED)
        if (restrictive && spec.requirements.approval != ApprovalRequirement.DenyAll)
            add(CompatibilityIssue("requirements.approval", "Restrictive execution constraints require DenyAll because approved escalation could exceed the required boundary"))
    })

    override fun sessionPayload(spec: SessionSpec) = buildJsonObject {
        effectiveInstructions(spec)?.let { put("instructions", it) }
        spec.model?.let { put("model", it) }
        reasoningEffort?.let { put("reasoningEffort", it) }
        if (spec.requirements.retention == ContextRetentionRequirement.Ephemeral) put("retention", "ephemeral")
        (spec.requirements.workspace as? WorkspaceRequirement.Required)?.let { workspace ->
            workspace.workingDirectory?.let { put("workingDirectory", it) }
            put("skills", buildJsonArray { workspace.skills.forEach { skill -> add(buildJsonObject {
                put("name", skill.name); put("path", skill.path); put("activate", skill.activate)
            }) } })
        }
        val execution = spec.requirements.execution as? ExecutionConstraint.Required
        put("filesystem", when (val fs = execution?.filesystem) {
            null -> "provider_default"
            FilesystemAccess.ReadOnly -> "read_only"
            FilesystemAccess.FullAccess -> "full_access"
            is FilesystemAccess.WorkspaceWrite -> { put("additionalWritableRoots", JsonArray(fs.additionalWritableRoots.map(::JsonPrimitive))); "workspace_write" }
        })
        put("network", execution?.network?.name?.lowercase() ?: "provider_default")
        put("approval", when (spec.requirements.approval) {
            ApprovalRequirement.ProviderDefault -> "provider_default"
            ApprovalRequirement.DenyAll -> "deny_all"
            ApprovalRequirement.AgentReviewed -> "agent_reviewed"
            ApprovalRequirement.CallerDecides -> "caller_decides"
        })
    }
    override fun ingest(task: ManagedTask, spec: SessionSpec, request: TaskRequest): (JsonObject) -> Unit =
        CodexTaskMapper(task, spec)::accept

    override suspend fun reasoningOptions(model: String?): ReasoningOptionCatalog {
        require(model == null || model.isNotBlank()) { "model must be null or non-blank" }
        val result = call("list_reasoning_options", buildJsonObject { model?.let { put("model", it) } })
        val catalog = ReasoningOptionCatalog(
            model = result.getValue("model").jsonPrimitive.content,
            options = result["options"]?.jsonArray.orEmpty().map { element ->
                val option = element.jsonObject
                ReasoningOptionDescriptor(
                    id = ReasoningOptionId(option.getValue("id").jsonPrimitive.content),
                    displayName = option.getValue("displayName").jsonPrimitive.content,
                    description = option["description"]?.jsonPrimitive?.contentOrNull,
                )
            },
            defaultOptionId = result["defaultOptionId"]?.jsonPrimitive?.contentOrNull?.let(::ReasoningOptionId),
        )
        reasoningCatalogs[catalogKey(model)] = catalog
        reasoningCatalogs[catalogKey(catalog.model)] = catalog
        return catalog
    }

    override fun taskIssues(spec: SessionSpec, request: TaskRequest): List<CompatibilityIssue> = buildList {
        addAll(super.taskIssues(spec, request))
        val selected = request.requirements.reasoning as? ReasoningOptionRequirement.Selected ?: return@buildList
        if (spec.model != selected.model) {
            add(CompatibilityIssue(
                "requirements.reasoning.model",
                "The selected reasoning option belongs to a different model than the session",
            ))
            return@buildList
        }
        val catalog = reasoningCatalogs[catalogKey(selected.model)]
        when {
            catalog == null -> add(CompatibilityIssue(
                "requirements.reasoning.optionId",
                "The current model reasoning option catalog has not been observed",
                CompatibilityIssueKind.UNCONFIRMED,
            ))
            catalog.options.none { it.id == selected.optionId } -> add(CompatibilityIssue(
                "requirements.reasoning.optionId",
                "The selected reasoning option is not available for the current model",
            ))
        }
    }

    override suspend fun taskAdmission(spec: SessionSpec, request: TaskRequest): CompatibilityReport {
        val base = super.taskIssues(spec, request)
        if (base.any { it.kind == CompatibilityIssueKind.UNSUPPORTED }) return CompatibilityReport(base)
        val selected = request.requirements.reasoning as? ReasoningOptionRequirement.Selected
            ?: return CompatibilityReport(base)
        if (spec.model != selected.model) return CompatibilityReport(base + CompatibilityIssue(
            "requirements.reasoning.model",
            "The selected reasoning option belongs to a different model than the session",
        ))
        val catalog = reasoningOptions(selected.model)
        return CompatibilityReport(base + if (catalog.options.any { it.id == selected.optionId }) emptyList() else listOf(
            CompatibilityIssue(
                "requirements.reasoning.optionId",
                "The selected reasoning option is not available for the current model",
            ),
        ))
    }

    override fun taskPayload(spec: SessionSpec, request: TaskRequest): JsonObject = buildJsonObject {
        (request.requirements.reasoning as? ReasoningOptionRequirement.Selected)?.let {
            put("reasoningOption", it.optionId.value)
        }
    }
    override fun sessionOpened(id: SessionId, spec: SessionSpec, resumed: Boolean) {
        storageNamespace?.let { namespace -> persistentSpecs.putIfAbsent(namespace to id, spec) }
    }
    override fun validateReopen(ref: PersistentSessionRef, spec: SessionSpec): CompatibilityReport {
        val retained = persistentSpecs[ref.namespace to SessionId(ref.id)] ?: return CompatibilityReport(listOf(
            CompatibilityIssue("persistentRef.id", "This adapter process has no desired-configuration record for the native thread")))
        return if (retained == spec) CompatibilityReport.Compatible else CompatibilityReport(listOf(
            CompatibilityIssue("spec", "This Codex App Server version does not apply changed session configuration on thread resume")))
    }

    private class Persistent(
        bridge: SdkBridge,
        scope: CoroutineScope,
        namespace: StorageNamespace,
        reasoningEffort: String?,
    ) : CodexHarness(bridge, scope, namespace, reasoningEffort), PersistentSessions {
        override suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec) = reopen(ref, spec)
    }
    companion object {
        private const val DEFAULT_MODEL_CATALOG = "<provider-default>"
        private val persistentSpecs = ConcurrentHashMap<Pair<StorageNamespace, SessionId>, SessionSpec>()
        private fun catalogKey(model: String?) = model ?: DEFAULT_MODEL_CATALOG
        fun launch(options: CodexSdkOptions = CodexSdkOptions(), storageNamespace: StorageNamespace? = null): CodexHarness {
            val script = options.bridgeScript ?: EmbeddedBridgeResource.extract(CodexHarness::class.java,
                "/dev/harnessprotocol/codex/codex_sdk_bridge.py", ".py")
            return usingBridge(JsonLineProcessBridge(
                command = options.pythonCommand + script.toAbsolutePath().toString(),
                workingDirectory = options.processWorkingDirectory,
                environment = options.hostEnvironment(),
                environmentMode = when (options.environmentMode) {
                    CodexSdkOptions.EnvironmentMode.INHERIT -> dev.harnessprotocol.bridge.ProcessEnvironmentMode.INHERIT
                    CodexSdkOptions.EnvironmentMode.REPLACE -> dev.harnessprotocol.bridge.ProcessEnvironmentMode.REPLACE
                },
            ), storageNamespace = storageNamespace, reasoningEffort = options.reasoningEffort)
        }
        fun usingBridge(
            bridge: SdkBridge,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            storageNamespace: StorageNamespace? = null,
            reasoningEffort: String? = null,
        ): CodexHarness = if (storageNamespace == null) {
            CodexHarness(bridge, scope, null, reasoningEffort)
        } else {
            Persistent(bridge, scope, storageNamespace, reasoningEffort)
        }
    }
}
