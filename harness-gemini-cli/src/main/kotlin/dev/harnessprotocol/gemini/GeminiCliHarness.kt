package dev.harnessprotocol.gemini

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

class GeminiCliHarness private constructor(
    private val bridge: SdkBridge,
    private val scope: CoroutineScope,
) : AgentHarness {
    override val provider: ProviderId = ProviderId("gemini-cli")
    private val runtime = BridgeHarnessRuntime(bridge, scope)

    override fun validate(spec: AgentSpec): CompatibilityReport {
        val issues = buildList {
            if (spec.executionPolicy.filesystem !is FilesystemAccess.ProviderDefault) {
                add(CompatibilityIssue("executionPolicy.filesystem", SDK_POLICY_LIMITATION))
            }
            if (spec.executionPolicy.network != NetworkAccess.PROVIDER_DEFAULT) {
                add(CompatibilityIssue("executionPolicy.network", SDK_POLICY_LIMITATION))
            }
            if (spec.executionPolicy.approval != ApprovalPolicy.PROVIDER_DEFAULT) {
                add(CompatibilityIssue("executionPolicy.approval", SDK_POLICY_LIMITATION))
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
        GeminiSession(runtime.open(id), spec, bridge, runtime)

    override fun close() {
        runBlocking { runtime.closeAll() }
        bridge.close()
        scope.cancel()
    }

    companion object {
        fun launch(options: GeminiCliSdkOptions): GeminiCliHarness {
            val bridgeScript = options.bridgeScript ?: EmbeddedBridgeResource.extract(
                owner = GeminiCliHarness::class.java,
                resourceName = "/dev/harnessprotocol/gemini/gemini_cli_sdk_bridge.mjs",
                suffix = ".mjs",
            )
            val environment = buildMap {
                putAll(options.environment)
                options.sdkModule?.let { put("GEMINI_CLI_SDK_MODULE", it) }
            }
            val bridge = JsonLineProcessBridge(
                command = options.nodeCommand + bridgeScript.toAbsolutePath().toString(),
                workingDirectory = options.processWorkingDirectory,
                environment = environment,
            )
            return GeminiCliHarness(bridge, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }

        fun usingBridge(
            bridge: SdkBridge,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): GeminiCliHarness = GeminiCliHarness(bridge, scope)
    }
}

data class GeminiCliSdkOptions(
    val bridgeScript: Path? = null,
    val sdkModule: String? = null,
    val nodeCommand: List<String> = listOf(
        System.getenv("HARNESS_GEMINI_NODE")?.takeIf(String::isNotBlank) ?: "node",
    ),
    val processWorkingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
)

private class GeminiSession(
    private val state: BridgeHarnessRuntime.SessionState,
    override val spec: AgentSpec,
    private val bridge: SdkBridge,
    private val runtime: BridgeHarnessRuntime,
) : AgentSession {
    override val id: SessionId get() = state.id

    override suspend fun execute(input: AgentInput): BridgeAgentExecution =
        runtime.execute(state, mapEvent = { executionId -> GeminiEventMapper(executionId)::map }) {
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
}

private fun AgentInput.toBridgeJson(): JsonObject = when (this) {
    is AgentInput.Text -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
}

private fun JsonObject.string(name: String): String =
    requireNotNull(this[name]) { "SDK bridge response is missing '$name': $this" }.jsonPrimitive.content

private const val SDK_POLICY_LIMITATION =
    "Gemini CLI has this policy purpose, but the current SDK does not expose a policy/approval bridge; refusing silent degradation"
