package dev.harnessprotocol.codex

import dev.harnessprotocol.legacy.AgentHarness
import dev.harnessprotocol.legacy.AgentInput
import dev.harnessprotocol.legacy.AgentSession
import dev.harnessprotocol.legacy.AgentSpec
import dev.harnessprotocol.legacy.ApprovalPolicy
import dev.harnessprotocol.legacy.CompatibilityIssue
import dev.harnessprotocol.legacy.CompatibilityReport
import dev.harnessprotocol.legacy.ExecutionId
import dev.harnessprotocol.legacy.FilesystemAccess
import dev.harnessprotocol.legacy.NetworkAccess
import dev.harnessprotocol.legacy.ProviderId
import dev.harnessprotocol.legacy.SessionId
import dev.harnessprotocol.bridge.BridgeAgentExecution
import dev.harnessprotocol.bridge.BridgeHarnessRuntime
import dev.harnessprotocol.bridge.EmbeddedBridgeResource
import dev.harnessprotocol.bridge.JsonLineProcessBridge
import dev.harnessprotocol.bridge.SdkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class CodexHarness private constructor(
    private val bridge: SdkBridge,
    private val scope: CoroutineScope,
) : AgentHarness {
    override val provider: ProviderId = ProviderId("codex")
    private val runtime = BridgeHarnessRuntime(bridge, scope)

    override fun validate(spec: AgentSpec): CompatibilityReport {
        val issues = buildList {
            val policy = spec.executionPolicy
            // Codex applies a network policy only through the workspace-write sandbox
            // configuration. Any other sandbox would silently ignore the intent.
            if (policy.network != NetworkAccess.PROVIDER_DEFAULT && policy.filesystem !is FilesystemAccess.WorkspaceWrite) {
                add(
                    CompatibilityIssue(
                        path = "executionPolicy.network",
                        message = "Codex applies a network policy only to the workspace-write sandbox; " +
                            "request FilesystemAccess.WorkspaceWrite or NetworkAccess.PROVIDER_DEFAULT",
                    ),
                )
            }
        }
        return CompatibilityReport(issues)
    }

    override suspend fun createSession(spec: AgentSpec): AgentSession {
        validate(spec).requireCompatible()
        val result = bridge.request("create_session", spec.toBridgeJson())
        return newSession(SessionId(result.string("sessionId")), spec)
    }

    override suspend fun resumeSession(id: SessionId, spec: AgentSpec): AgentSession {
        validate(spec).requireCompatible()
        val result = bridge.request(
            "resume_session",
            buildJsonObject {
                put("sessionId", id.value)
                put("spec", spec.toBridgeJson())
            },
        )
        // The host may normalize the ID; the response is authoritative.
        return newSession(SessionId(result.string("sessionId")), spec)
    }

    private fun newSession(id: SessionId, spec: AgentSpec): AgentSession =
        CodexSession(runtime.open(id), spec, bridge, runtime)

    override fun close() {
        runBlocking { runtime.closeAll() }
        bridge.close()
        scope.cancel()
    }

    companion object {
        fun launch(options: CodexSdkOptions): CodexHarness {
            val bridgeScript = options.bridgeScript ?: EmbeddedBridgeResource.extract(
                owner = CodexHarness::class.java,
                resourceName = "/dev/harnessprotocol/codex/codex_sdk_bridge.py",
                suffix = ".py",
            )
            val bridge = JsonLineProcessBridge(
                command = options.pythonCommand + bridgeScript.toAbsolutePath().toString(),
                workingDirectory = options.processWorkingDirectory,
                environment = codexHostEnvironment(options),
            )
            return CodexHarness(bridge, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }

        fun usingBridge(
            bridge: SdkBridge,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): CodexHarness = CodexHarness(bridge, scope)
    }
}

data class CodexSdkOptions(
    val bridgeScript: Path? = null,
    val pythonCommand: List<String> = listOf(
        System.getenv("HARNESS_CODEX_PYTHON")?.takeIf(String::isNotBlank) ?: "python",
    ),
    val processWorkingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    /** Specific Codex executable for the Python SDK; `null` uses its pinned runtime. */
    val codexExecutable: Path? = null,
)

internal fun codexHostEnvironment(options: CodexSdkOptions): Map<String, String> = buildMap {
    putAll(options.environment)
    remove(CODEX_EXECUTABLE_ENV)
    options.codexExecutable?.let { put(CODEX_EXECUTABLE_ENV, it.toAbsolutePath().normalize().toString()) }
}

internal const val CODEX_EXECUTABLE_ENV = "HARNESS_CODEX_EXECUTABLE"

private class CodexSession(
    private val state: BridgeHarnessRuntime.SessionState,
    override val spec: AgentSpec,
    private val bridge: SdkBridge,
    private val runtime: BridgeHarnessRuntime,
) : AgentSession {
    override val id: SessionId get() = state.id

    override suspend fun execute(input: AgentInput): BridgeAgentExecution =
        runtime.execute(state, mapEvent = { executionId -> CodexEventMapper(executionId)::map }) {
            val result = bridge.request(
                "start_execution",
                buildJsonObject {
                    put("sessionId", id.value)
                    put("input", input.toBridgeJson())
                },
            )
            ExecutionId(result.string("executionId"))
        }

    override suspend fun release() = runtime.release(state)
}

private fun AgentSpec.toBridgeJson(): JsonObject = buildJsonObject {
    instructions?.let { put("instructions", it) }
    model?.let { put("model", it) }
    workingDirectory?.let { put("workingDirectory", it) }
    put("skills", buildJsonArray {
        skills.forEach { skill ->
            add(buildJsonObject {
                put("name", skill.name)
                put("path", skill.path)
            })
        }
    })
    put("filesystem", when (executionPolicy.filesystem) {
        FilesystemAccess.ProviderDefault -> "provider_default"
        FilesystemAccess.ReadOnly -> "read_only"
        is FilesystemAccess.WorkspaceWrite -> "workspace_write"
        FilesystemAccess.FullAccess -> "full_access"
    })
    val writableRoots = (executionPolicy.filesystem as? FilesystemAccess.WorkspaceWrite)
        ?.additionalWritableRoots.orEmpty()
    put("additionalWritableRoots", JsonArray(writableRoots.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    put("network", when (executionPolicy.network) {
        NetworkAccess.PROVIDER_DEFAULT -> "provider_default"
        NetworkAccess.DENIED -> "denied"
        NetworkAccess.ALLOWED -> "allowed"
    })
    put("approval", when (executionPolicy.approval) {
        ApprovalPolicy.PROVIDER_DEFAULT -> "provider_default"
        ApprovalPolicy.DENY_ALL -> "deny_all"
        ApprovalPolicy.AGENT_REVIEWED -> "agent_reviewed"
        ApprovalPolicy.CALLER_DECIDES -> "caller_decides"
    })
}

private fun AgentInput.toBridgeJson(): JsonObject = when (this) {
    is AgentInput.Text -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
}

private fun JsonObject.string(name: String): String =
    requireNotNull(this[name]) { "SDK bridge response is missing '$name': $this" }
        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
