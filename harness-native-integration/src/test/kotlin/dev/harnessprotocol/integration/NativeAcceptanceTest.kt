package dev.harnessprotocol.integration

import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.*
import dev.harnessprotocol.codex.CodexHarness
import dev.harnessprotocol.gemini.GeminiCliHarness
import dev.harnessprotocol.conformance.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CodexNativeStartAcceptanceTest : HarnessStartAcceptanceConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun acceptanceFixture(): StartAcceptanceFixture = NativeStartFixture(CodexNativeFactory, directory)
}
class GeminiNativeStartAcceptanceTest : HarnessStartAcceptanceConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun acceptanceFixture(): StartAcceptanceFixture = NativeStartFixture(GeminiNativeFactory, directory)
}
class KoogNativeAcceptedStartTest : HarnessAcceptedStartConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun acceptanceFixture(): AcceptanceFixture = object : AcceptanceFixture {
        override val observation = ModelBoundary()
        override val spec = KoogNativeFactory.spec()
        override val harness = KoogNativeFactory.create(observation, directory)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
    @Test fun `local start hands back its handle without an acknowledgement suspension`() = runBlocking<Unit> {
        acceptanceFixture().use { f ->
            f.observation.hold()
            val session = f.harness.createSession(f.spec)
            val start = async(start = CoroutineStart.UNDISPATCHED) { session.startTask(TaskRequest(TaskInput.Text("local-handoff"))) }
            assertTrue(start.isCompleted, "Reassess acknowledgement-loss coverage if the local handoff becomes suspending")
            val task = start.await()
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
        }
    }
}

private class NativeStartFixture(factory: NativeHarnessFactory, directory: Path) : StartAcceptanceFixture {
    override val observation = ModelBoundary()
    override val spec = factory.spec()
    private val delivery = NativeDeliveryBridge(nativeBridge(factory, observation, directory))
    override val start = delivery.startControl
    override val submittedStarts: List<UnconfirmedStart> get() = delivery.startReferences.toList()
    override val harness = processHarness(factory, delivery)
    override fun close() { try { harness.close() } finally { observation.close() } }
}

class CodexNativeResponseAcceptanceTest : HarnessResponseAcceptanceConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun responseFixture(): ResponseAcceptanceFixture = NativeResponseFixture(directory)
}

internal class NativeResponseFixture(directory: Path) : InteractionRaceFixture {
    private val target = directory.resolve("approved-effect.txt")
    private val calls = AtomicInteger()
    override val observation: ModelBoundary = createModel()
    private fun createModel() = ModelBoundary { body ->
        if (calls.getAndIncrement() == 0) {
            val request = Json.parseToJsonElement(body).jsonObject
            val tools = request["tools"]!!.jsonArray.map { it.jsonObject }
            val names = tools.mapNotNull { it["name"]?.jsonPrimitive?.content }
            val name = names.firstOrNull { it in setOf("exec_command", "shell_command", "shell") }
                ?: error("No command tool in the real model request: $names")
            observation.hold() // The post-tool model response must not overtake the caller's acknowledgement.
            val command = "[System.IO.File]::AppendAllText('${target.toString().replace("'", "''")}', 'effect')"
            val args = if (name == "exec_command") buildJsonObject {
                put("cmd", command); put("sandbox_permissions", "require_escalated"); put("justification", "Write the isolated AHP test marker")
            } else if (name == "shell_command") buildJsonObject {
                put("command", command); put("sandbox_permissions", "require_escalated"); put("justification", "Write the isolated AHP test marker")
            } else buildJsonObject {
                put("command", JsonArray(listOf("powershell.exe", "-NoProfile", "-Command", command).map(::JsonPrimitive)))
                put("sandbox_permissions", "require_escalated"); put("justification", "Write the isolated AHP test marker")
            }
            val item = buildJsonObject {
                put("id", "fc_ahp"); put("type", "function_call"); put("call_id", "call_ahp")
                put("name", name); put("arguments", args.toString()); put("status", "completed")
            }
            listOf(
                buildJsonObject { put("type", "response.created"); put("response", buildJsonObject { put("id", "resp_tool"); put("status", "in_progress"); put("output", JsonArray(emptyList())) }) },
                buildJsonObject { put("type", "response.output_item.done"); put("output_index", 0); put("item", item) },
                buildJsonObject { put("type", "response.completed"); put("response", buildJsonObject { put("id", "resp_tool"); put("status", "completed"); put("output", JsonArray(listOf(item))) }) },
            ).joinToString("") { "data: $it\n\n" }
        } else null
    }
    override val spec = CodexNativeFactory.spec().copy(requirements = SessionRequirements(
        approval = ApprovalRequirement.CallerDecides,
        execution = ExecutionConstraint.Required(filesystem = FilesystemAccess.ReadOnly),
    ))
    private val delivery = NativeDeliveryBridge(nativeBridge(CodexNativeFactory, observation, directory))
    override val response = delivery.responseControl
    override fun holdResponse() = delivery.holdResponse()
    override suspend fun awaitResponseSubmission() = delivery.responseSubmitted.await()
    override fun releaseResponse() = delivery.releaseResponse()
    override val harness = processHarness(CodexNativeFactory, delivery)
    override fun effectCount(): Int {
        if (!Files.exists(target)) return 0
        val markers = Files.readString(target).chunked("effect".length)
        check(markers.all { it == "effect" }) { "Unexpected content in the native effect resource" }
        return markers.size
    }
    override fun close() { releaseResponse(); try { harness.close() } finally { observation.close() } }
}

private fun processHarness(factory: NativeHarnessFactory, bridge: SdkBridge): AgentHarness = when (factory) {
    CodexNativeFactory -> CodexHarness.usingBridge(bridge)
    GeminiNativeFactory -> GeminiCliHarness.usingBridge(bridge)
    else -> error("No request/acknowledgement bridge exists in the configured local graph adapter")
}

private fun nativeBridge(factory: NativeHarnessFactory, model: ModelBoundary, directory: Path): JsonLineProcessBridge = when (factory) {
    CodexNativeFactory -> {
        val options = CodexNativeFactory.options(model, directory)
        val script = EmbeddedBridgeResource.extract(CodexHarness::class.java, "/dev/harnessprotocol/codex/codex_sdk_bridge.py", ".py")
        JsonLineProcessBridge(options.pythonCommand + script.toString(), directory, options.environment)
    }
    GeminiNativeFactory -> {
        val options = GeminiNativeFactory.options(model, directory)
        val script = EmbeddedBridgeResource.extract(GeminiCliHarness::class.java, "/dev/harnessprotocol/gemini/gemini_cli_sdk_bridge.mjs", ".mjs")
        JsonLineProcessBridge(options.nodeCommand + script.toString(), directory, options.environment + mapOf("GEMINI_CLI_SDK_MODULE" to requireNotNull(options.sdkModule)))
    }
    else -> error("No native transport")
}

/** Delivery fault decoration around the real SDK host. It never manufactures a Port handle or event. */
private class NativeDeliveryBridge(private val delegate: ConfirmedSdkBridge) : ConfirmedSdkBridge {
    private var responseGate: CompletableDeferred<Unit>? = null
    val responseSubmitted = CompletableDeferred<Unit>()
    fun holdResponse() { responseGate = CompletableDeferred() }
    fun releaseResponse() { responseGate?.complete(Unit) }
    private enum class Mode { NORMAL, NOT_DELIVERED, LOST_ACCEPTED, LOST_NOT_ACCEPTED }
    @Volatile private var startMode = Mode.NORMAL
    @Volatile private var responseMode = Mode.NORMAL
    private val starts = AtomicInteger()
    val startReferences = CopyOnWriteArrayList<UnconfirmedStart>()
    private val acceptedStarts = AtomicInteger()
    private val responses = AtomicInteger()
    private val acceptedResponses = CopyOnWriteArrayList<InteractionResponse>()
    val startControl = object : StartControl {
        override fun accept() { startMode = Mode.NORMAL }
        override fun rejectBeforeDelivery(message: String) { startMode = Mode.NOT_DELIVERED }
        override fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean) { startMode = if (acceptedByRuntime) Mode.LOST_ACCEPTED else Mode.LOST_NOT_ACCEPTED }
        override fun observedSubmissions() = starts.get()
        override fun observedAcceptedStarts() = acceptedStarts.get()
    }
    val responseControl = object : ResponseControl {
        override fun accept() { responseMode = Mode.NORMAL }
        override fun rejectBeforeDelivery(message: String) { responseMode = Mode.NOT_DELIVERED }
        override fun loseAcceptanceAcknowledgement(acceptedByRuntime: Boolean) { responseMode = if (acceptedByRuntime) Mode.LOST_ACCEPTED else Mode.LOST_NOT_ACCEPTED }
        override fun observedSubmissions() = responses.get()
        override fun observedAcceptedResponses(): List<InteractionResponse> = acceptedResponses.toList()
    }
    override suspend fun request(method: String, params: JsonObject) = requestConfirmed(method, params)
    override suspend fun requestConfirmed(method: String, params: JsonObject): JsonObject {
        val mode = when (method) {
            "start_execution" -> {
                starts.incrementAndGet()
                startReferences += UnconfirmedStart(SessionId(params["sessionId"]!!.jsonPrimitive.content), params["requestId"]!!.jsonPrimitive.content)
                startMode
            }
            "respond_interaction" -> {
                responses.incrementAndGet()
                responseSubmitted.complete(Unit)
                responseGate?.await()
                responseMode
            }
            else -> Mode.NORMAL
        }
        if (mode == Mode.NOT_DELIVERED) throw BridgeNotDeliveredException("No request was forwarded to the native host")
        if (mode == Mode.LOST_NOT_ACCEPTED) throw BridgeAcceptanceUnconfirmedException("Acknowledgement path lost; request was not forwarded")
        val result = delegate.requestConfirmed(method, params)
        if (method == "start_execution") acceptedStarts.incrementAndGet()
        if (method == "respond_interaction") acceptedResponses += InteractionResponse.Approval(
            ApprovalDecision.valueOf(params["response"]!!.jsonObject["decision"]!!.jsonPrimitive.content.uppercase()))
        if (mode == Mode.LOST_ACCEPTED) throw BridgeAcceptanceUnconfirmedException("Actual native acceptance acknowledgement was discarded")
        return result
    }
    override fun events(executionId: String) = delegate.events(executionId).filter { event ->
        // Drop the duplicate acknowledgement channel too; otherwise acceptance is independently confirmed.
        !(responseMode == Mode.LOST_ACCEPTED && event["method"]?.jsonPrimitive?.content == "interaction_resolved" &&
            event["payload"]?.jsonObject?.get("resolution")?.jsonObject?.get("type")?.jsonPrimitive?.content == "responded")
    }
    override fun release(executionId: String) = delegate.release(executionId)
    override fun close() = delegate.close()
}
