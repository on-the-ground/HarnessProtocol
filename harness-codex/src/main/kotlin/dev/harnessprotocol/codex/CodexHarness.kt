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
) : ProcessTaskHarness(bridge, scope, storageNamespace = namespace) {
    override val provider = ProviderId("codex")
    override val support = SupportReport(mapOf(
        Capability.CALLER_APPROVAL to Support.Supported,
        Capability.QUESTIONS to Support.Unsupported("The configured client exposes approval handlers, not a typed question channel"),
        Capability.PERSISTENCE to if (namespace != null) Support.Conditional(SupportScope.SESSION, "Same application process; no concurrent access") else Support.Unsupported("Configure storageNamespace"),
        Capability.WORKSPACE to Support.Supported,
        Capability.EXECUTION_CONSTRAINT to Support.Conditional(SupportScope.SESSION, "Network policy requires an explicit workspace-write sandbox"),
        Capability.STRUCTURED_OUTPUT to Support.Unsupported("Schema enforcement is not configured"),
        Capability.DIAGNOSTICS to Support.Supported,
    ))
    override fun validate(spec: SessionSpec) = CompatibilityReport(buildList {
        addAll(persistenceIssues(spec))
        if (spec.requirements.questions != QuestionRequirement.NotRequired)
            add(CompatibilityIssue("requirements.questions", "Typed question mediation is not exposed by this client connection"))
        val execution = spec.requirements.execution as? ExecutionConstraint.Required
        if (execution?.network != null && execution.filesystem !is FilesystemAccess.WorkspaceWrite)
            add(CompatibilityIssue("requirements.execution.network", "Codex network policy requires workspace-write; the adapter will not silently change filesystem policy"))
    })

    override fun sessionPayload(spec: SessionSpec) = buildJsonObject {
        spec.instructions?.let { put("instructions", it) }
        spec.model?.let { put("model", it) }
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
    override fun sessionOpened(id: SessionId, spec: SessionSpec, resumed: Boolean) {
        storageNamespace?.let { namespace -> persistentSpecs.putIfAbsent(namespace to id, spec) }
    }
    override fun validateReopen(ref: PersistentSessionRef, spec: SessionSpec): CompatibilityReport {
        val retained = persistentSpecs[ref.namespace to SessionId(ref.id)] ?: return CompatibilityReport(listOf(
            CompatibilityIssue("persistentRef.id", "This adapter process has no desired-configuration record for the native thread")))
        return if (retained == spec) CompatibilityReport.Compatible else CompatibilityReport(listOf(
            CompatibilityIssue("spec", "This Codex App Server version does not apply changed session configuration on thread resume")))
    }

    private class Persistent(bridge: SdkBridge, scope: CoroutineScope, namespace: StorageNamespace) :
        CodexHarness(bridge, scope, namespace), PersistentSessions {
        override suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec) = reopen(ref, spec)
    }
    companion object {
        private val persistentSpecs = ConcurrentHashMap<Pair<StorageNamespace, SessionId>, SessionSpec>()
        fun launch(options: CodexSdkOptions = CodexSdkOptions(), storageNamespace: StorageNamespace? = null): CodexHarness {
            val script = options.bridgeScript ?: EmbeddedBridgeResource.extract(CodexHarness::class.java,
                "/dev/harnessprotocol/codex/codex_sdk_bridge.py", ".py")
            return usingBridge(JsonLineProcessBridge(options.pythonCommand + script.toAbsolutePath().toString(),
                options.processWorkingDirectory, options.environment), storageNamespace = storageNamespace)
        }
        fun usingBridge(bridge: SdkBridge, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), storageNamespace: StorageNamespace? = null): CodexHarness =
            if (storageNamespace == null) CodexHarness(bridge, scope, null) else Persistent(bridge, scope, storageNamespace)
    }
}
