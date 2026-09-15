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
) : ProcessTaskHarness(bridge, scope, storageNamespace = namespace) {
    init {
        require(reasoningEffort == null || reasoningEffort.isNotBlank()) {
            "reasoningEffort must be null or non-blank"
        }
    }

    override val provider = ProviderId("codex")
    private val reasoningCatalogs = ConcurrentHashMap<String, CodexReasoningOptionCatalog>()
    private val unavailableReasoningModels = ConcurrentHashMap.newKeySet<String>()
    private val reasoningCatalogLock = Any()
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

    /**
     * Codex `model/list`에서 현재 reasoning option catalog을 조회한다.
     *
     * [model]을 생략하면 provider default model을 요청한다. 결과는 이 조회 시점의
     * snapshot이며 이후 Task 시작 수락을 보장하지 않는다.
     *
     * @throws HarnessTransportException model catalog을 조회하지 못했을 때
     */
    suspend fun reasoningOptions(model: String? = null): CodexReasoningOptionLookup {
        require(model == null || model.isNotBlank()) { "model must be null or non-blank" }
        val result = call("list_reasoning_options", buildJsonObject { model?.let { put("model", it) } })
        if (result["found"]?.jsonPrimitive?.booleanOrNull != true) {
            model?.let(::forgetReasoningCatalog)
            return CodexReasoningOptionLookup.NotFound(model)
        }
        val canonical = CodexModelId(result.getValue("canonicalModel").jsonPrimitive.content)
        val aliases = result["aliases"]?.jsonArray.orEmpty().mapTo(linkedSetOf()) {
            it.jsonPrimitive.content
        }
        val catalog = CodexReasoningOptionCatalog(
            model = CodexModelIdentity(canonical, aliases),
            options = result["options"]?.jsonArray.orEmpty().map { element ->
                val option = element.jsonObject
                CodexReasoningOptionDescriptor(
                    id = CodexReasoningOptionId(option.getValue("id").jsonPrimitive.content),
                    displayName = option.getValue("displayName").jsonPrimitive.content,
                    description = option["description"]?.jsonPrimitive?.contentOrNull,
                )
            },
            defaultOptionId = result["defaultOptionId"]?.jsonPrimitive?.contentOrNull
                ?.let(::CodexReasoningOptionId),
        )
        rememberReasoningCatalog(model, catalog)
        return CodexReasoningOptionLookup.Available(catalog)
    }

    private fun rememberReasoningCatalog(
        requestedModel: String?,
        catalog: CodexReasoningOptionCatalog,
    ) = synchronized(reasoningCatalogLock) {
        val keys = catalog.model.aliases + catalog.model.canonicalModel.value + listOfNotNull(requestedModel)
        keys.mapNotNull(reasoningCatalogs::get).toSet().forEach { stale ->
            reasoningCatalogs.entries.removeIf { it.value === stale }
        }
        keys.forEach { key ->
            unavailableReasoningModels -= key
            reasoningCatalogs[key] = catalog
        }
    }

    private fun forgetReasoningCatalog(model: String) = synchronized(reasoningCatalogLock) {
        unavailableReasoningModels += model
        reasoningCatalogs[model]?.let { stale ->
            reasoningCatalogs.entries.removeIf { it.value === stale }
        }
    }

    private fun reasoningCatalog(model: String): CodexReasoningOptionCatalog? =
        synchronized(reasoningCatalogLock) { reasoningCatalogs[model] }

    private fun reasoningModelUnavailable(model: String): Boolean =
        synchronized(reasoningCatalogLock) { model in unavailableReasoningModels }

    /** Creates a session wrapper that can atomically apply Codex-only Task options. */
    suspend fun createCodexSession(spec: SessionSpec): CodexTaskSession =
        CodexSession(createSession(spec))

    private inner class CodexSession(
        private val delegate: AgentSession,
    ) : CodexTaskSession, AgentSession by delegate {
        override fun validate(
            request: TaskRequest,
            options: CodexTaskOptions,
        ): CompatibilityReport = CompatibilityReport(
            delegate.validate(request).issues + reasoningIssues(delegate.spec.model, options.reasoning),
        )

        override suspend fun startTask(
            request: TaskRequest,
            options: CodexTaskOptions,
        ): AgentTask {
            val common = delegate.validate(request)
            common.requireCompatible()
            val selection = options.reasoning ?: return delegate.startTask(request)
            val selectedCatalog = when (val lookup = reasoningOptions(selection.model.value)) {
                is CodexReasoningOptionLookup.Available -> lookup.catalog
                is CodexReasoningOptionLookup.NotFound -> throw IncompatibleRequirementException(
                    listOf(CompatibilityIssue(
                        "codex.reasoning.model",
                        "The selected Codex model is no longer present in model/list",
                    )),
                )
            }
            val sessionModel = delegate.spec.model
            val sessionCatalog = when {
                sessionModel == null -> null
                selectedCatalog.model.matches(sessionModel) -> selectedCatalog
                else -> when (val lookup = reasoningOptions(sessionModel)) {
                    is CodexReasoningOptionLookup.Available -> lookup.catalog
                    is CodexReasoningOptionLookup.NotFound -> null
                }
            }
            CompatibilityReport(
                common.issues + reasoningIssues(sessionModel, selection, selectedCatalog, sessionCatalog),
            ).requireCompatible()
            return startTaskWithAdapterPayload(delegate, request, buildJsonObject {
                put("reasoningOption", selection.optionId.value)
            })
        }
    }

    private fun reasoningIssues(
        sessionModel: String?,
        selection: CodexReasoningOptionSelection?,
        selectedCatalog: CodexReasoningOptionCatalog? = selection?.let {
            reasoningCatalog(it.model.value)
        },
        sessionCatalog: CodexReasoningOptionCatalog? = sessionModel?.let(::reasoningCatalog),
    ): List<CompatibilityIssue> {
        if (selection == null) return emptyList()
        if (selectedCatalog == null) {
            val unavailable = reasoningModelUnavailable(selection.model.value)
            return listOf(CompatibilityIssue(
                "codex.reasoning.catalog",
                if (unavailable) {
                    "The selected Codex model is no longer present in model/list"
                } else {
                    "The selected Codex reasoning catalog has not been observed"
                },
                if (unavailable) CompatibilityIssueKind.UNSUPPORTED else CompatibilityIssueKind.UNCONFIRMED,
            ))
        }
        if (selectedCatalog.model.canonicalModel != selection.model) return listOf(CompatibilityIssue(
            "codex.reasoning.model",
            "The selected reasoning option is bound to a different canonical Codex model",
        ))
        if (selectedCatalog.options.none { it.id == selection.optionId }) return listOf(CompatibilityIssue(
            "codex.reasoning.optionId",
            "The selected reasoning option is not available for the Codex model",
        ))
        if (sessionModel == null) return listOf(CompatibilityIssue(
            "codex.reasoning.model",
            "The session uses an unobserved provider-default model; pin the catalog canonical model",
            CompatibilityIssueKind.UNCONFIRMED,
        ))
        if (!selectedCatalog.model.matches(sessionModel)) {
            return when {
                sessionCatalog == null -> listOf(CompatibilityIssue(
                    "codex.reasoning.model",
                    "The session model is not a confirmed alias of the selected catalog model",
                    CompatibilityIssueKind.UNCONFIRMED,
                ))
                sessionCatalog.model.canonicalModel != selectedCatalog.model.canonicalModel -> listOf(
                    CompatibilityIssue(
                        "codex.reasoning.model",
                        "The session and reasoning option use different Codex models",
                    ),
                )
                else -> emptyList()
            }
        }
        return emptyList()
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
        private val persistentSpecs = ConcurrentHashMap<Pair<StorageNamespace, SessionId>, SessionSpec>()
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
