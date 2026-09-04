package dev.harnessprotocol.gemini

import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Task port connected to the official Gemini CLI SDK's session/sendStream lifecycle. */
open class GeminiCliHarness protected constructor(bridge: SdkBridge, scope: CoroutineScope, namespace: StorageNamespace?) :
    ProcessTaskHarness(bridge, scope, storageNamespace = namespace) {
    override val provider = ProviderId("gemini-cli")
    override val support = SupportReport(mapOf(
        Capability.CALLER_APPROVAL to Support.Unsupported("The SDK connection has no approval handler"),
        Capability.QUESTIONS to Support.Unsupported("The SDK connection has no typed question handler"),
        Capability.PERSISTENCE to if (namespace != null) Support.Conditional(SupportScope.SESSION, "Same application process; no concurrent access") else Support.Unsupported("Configure storageNamespace"),
        Capability.WORKSPACE to Support.Supported,
        Capability.EXECUTION_CONSTRAINT to Support.Unsupported("The SDK does not expose policy enforcement"),
        Capability.STRUCTURED_OUTPUT to Support.Unsupported("Schema enforcement is not configured"),
        Capability.DIAGNOSTICS to Support.Supported,
    ))
    override fun validate(spec: SessionSpec) = CompatibilityReport(buildList {
        addAll(persistenceIssues(spec))
        if (spec.requirements.approval != ApprovalRequirement.ProviderDefault)
            add(CompatibilityIssue("requirements.approval", "The SDK does not expose approval mediation"))
        if (spec.requirements.questions != QuestionRequirement.NotRequired)
            add(CompatibilityIssue("requirements.questions", "The SDK does not expose typed questions"))
        if (spec.requirements.execution != ExecutionConstraint.ProviderDefault)
            add(CompatibilityIssue("requirements.execution", "The SDK does not expose filesystem or network enforcement"))
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
    }
    override fun ingest(task: ManagedTask, spec: SessionSpec, request: TaskRequest): (JsonObject) -> Unit =
        GeminiTaskMapper(task, spec)::accept
    private class Persistent(bridge: SdkBridge, scope: CoroutineScope, namespace: StorageNamespace) :
        GeminiCliHarness(bridge, scope, namespace), PersistentSessions {
        override suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec) = reopen(ref, spec)
    }
    companion object {
        fun launch(options: GeminiCliSdkOptions = GeminiCliSdkOptions(), storageNamespace: StorageNamespace? = null): GeminiCliHarness {
            val script = options.bridgeScript ?: EmbeddedBridgeResource.extract(GeminiCliHarness::class.java,
                "/dev/harnessprotocol/gemini/gemini_cli_sdk_bridge.mjs", ".mjs")
            val environment = options.environment + (options.sdkModule?.let { mapOf("GEMINI_CLI_SDK_MODULE" to it) } ?: emptyMap())
            return usingBridge(JsonLineProcessBridge(options.nodeCommand + script.toAbsolutePath().toString(),
                options.processWorkingDirectory, environment), storageNamespace = storageNamespace)
        }
        fun usingBridge(bridge: SdkBridge, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), storageNamespace: StorageNamespace? = null): GeminiCliHarness =
            if (storageNamespace == null) GeminiCliHarness(bridge, scope, null) else Persistent(bridge, scope, storageNamespace)
    }
}
