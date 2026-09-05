package dev.harnessprotocol.integration

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.sun.net.httpserver.HttpServer
import dev.harnessprotocol.*
import dev.harnessprotocol.codex.*
import dev.harnessprotocol.gemini.*
import dev.harnessprotocol.koog.KoogHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Native construction only. Reusable assertions live in harness-conformance. */
abstract class NativeHarnessTest : dev.harnessprotocol.conformance.HarnessRuntimeProfileConformanceTest<ModelBoundary>() {
    @TempDir lateinit var directory: Path
    override fun boundary() = ModelBoundary()
}
abstract class NativePersistentHarnessTest : dev.harnessprotocol.conformance.HarnessRuntimePersistenceConformanceTest<ModelBoundary>() {
    @TempDir lateinit var directory: Path
    override fun boundary() = ModelBoundary()
}

interface NativeHarnessFactory {
    val provider: ProviderId
    fun spec() = SessionSpec(instructions = "AHP_NATIVE_INSTRUCTION")
    fun create(model: ModelBoundary, directory: Path, persistent: Boolean = true): AgentHarness
}

class CodexNativeHarnessTest : NativePersistentHarnessTest() {
    override val supportsChangedInstructionsOnReopen = false
    override fun harness(model: ModelBoundary) = CodexNativeFactory.create(model, directory)

    @Test
    fun `ephemeral retention is confirmed after a real native turn without rollout materialization`() = runBlocking<Unit> {
        ModelBoundary().use { model ->
            val isolated = Files.createDirectories(directory.resolve("ephemeral"))
            CodexHarness.launch(CodexNativeFactory.options(model, isolated)).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
                val session = harness.createSession(spec)
                assertEquals(ContextRetentionDisposition.EPHEMERAL, session.disposition.retention)
                assertEquals(UserHistoryVisibility.UNKNOWN, session.disposition.historyVisibility)
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                    session.startTask(TaskRequest(TaskInput.Text("ephemeral marker-epsilon"))).awaitOutcome()
                })
                session.release()
            }
            Files.walk(isolated.resolve("codex-home")).use { paths ->
                kotlin.test.assertFalse(paths.anyMatch {
                    Files.isRegularFile(it) && it.fileName.toString().startsWith("rollout-")
                })
            }
        }
    }
}
class GeminiNativeHarnessTest : NativePersistentHarnessTest() {
    override fun spec() = GeminiNativeFactory.spec()
    override fun harness(model: ModelBoundary) = GeminiNativeFactory.create(model, directory)
}
class KoogNativeHarnessTest : NativeHarnessTest() {
    override fun harness(model: ModelBoundary) = KoogNativeFactory.create(model, directory)

    @Test fun `null instructions use the configured default while explicit empty instructions stay empty`() = runBlocking<Unit> {
        val systems = CopyOnWriteArrayList<String>()
        val harness = KoogHarness({ object : PromptExecutor() {
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                systems += prompt.messages.filterIsInstance<Message.System>().single().textContent()
                return Message.Assistant("native-result", ResponseMetaInfo.Empty)
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Streaming is not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is not used")
            override fun close() = Unit
        } }, OpenAIModels.Chat.GPT4o, defaultInstructions = "AHP_CONFIGURED_DEFAULT")
        harness.use { h ->
            val omitted = h.createSession(SessionSpec(instructions = null))
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                omitted.startTask(TaskRequest(TaskInput.Text("omitted"))).awaitOutcome()
            })
            val empty = h.createSession(SessionSpec(instructions = ""))
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                empty.startTask(TaskRequest(TaskInput.Text("empty"))).awaitOutcome()
            })
        }
        assertEquals(listOf("AHP_CONFIGURED_DEFAULT", ""), systems)
    }
}

object CodexNativeFactory : NativeHarnessFactory {
    override val provider = ProviderId("codex")
    override fun create(model: ModelBoundary, directory: Path, persistent: Boolean): AgentHarness =
        CodexHarness.launch(options(model, directory), storageNamespace = if (persistent) StorageNamespace(directory.toString()) else null)
    fun options(model: ModelBoundary, directory: Path): CodexSdkOptions {
        val repo = Path.of(System.getProperty("ahp.repository"))
        val home = Files.createDirectories(directory.resolve("codex-home"))
        Files.writeString(home.resolve("config.toml"), """
            model = "ahp-fixture"
            model_provider = "ahp_fixture"
            [model_providers.ahp_fixture]
            name = "AHP local model boundary"
            base_url = "${model.url}/v1"
            wire_api = "responses"
            requires_openai_auth = false
        """.trimIndent())
        val python = System.getenv("HARNESS_CODEX_PYTHON") ?: repo.resolve(".venv/Scripts/python.exe").toString()
        return CodexSdkOptions(pythonCommand = listOf(python), processWorkingDirectory = directory,
            environment = mapOf("CODEX_HOME" to home.toString()))
    }
}
object GeminiNativeFactory : NativeHarnessFactory {
    override val provider = ProviderId("gemini-cli")
    override fun spec() = SessionSpec(instructions = "AHP_NATIVE_INSTRUCTION", model = "gemini-2.5-flash")
    override fun create(model: ModelBoundary, directory: Path, persistent: Boolean): AgentHarness =
        GeminiCliHarness.launch(options(model, directory), storageNamespace = if (persistent) StorageNamespace(directory.toString()) else null)
    fun options(model: ModelBoundary, directory: Path): GeminiCliSdkOptions {
        val repo = Path.of(System.getProperty("ahp.repository"))
        val module = System.getenv("GEMINI_CLI_SDK_MODULE") ?: repo.resolve("_stage/gemini-cli-runtime/packages/sdk/dist/index.js").toString()
        check(Files.exists(Path.of(module))) { "Build the official Gemini SDK and set GEMINI_CLI_SDK_MODULE" }
        return GeminiCliSdkOptions(sdkModule = module, processWorkingDirectory = directory,
            environment = mapOf("GOOGLE_GEMINI_BASE_URL" to model.url, "GEMINI_API_KEY" to "local-fixture-key", "GEMINI_TELEMETRY_ENABLED" to "false",
                "USERPROFILE" to Files.createDirectories(directory.resolve("gemini-home")).toString(), "HOME" to directory.resolve("gemini-home").toString()))
    }
}
object KoogNativeFactory : NativeHarnessFactory {
    override val provider = ProviderId("koog")
    override fun create(model: ModelBoundary, directory: Path, persistent: Boolean): AgentHarness {
        val boundary = model
        return KoogHarness({
            object : PromptExecutor() {
                override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                    boundary.record(prompt)
                    boundary.awaitRelease()
                    return Message.Assistant("native-result", ResponseMetaInfo.Empty)
                }
                override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Streaming is not used")
                override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is not used")
                override fun close() = Unit
            }
        }, OpenAIModels.Chat.GPT4o)
    }
}

/** Model protocol server, never a harness or Port implementation. No external model calls. */
class ModelBoundary(val overrideResponse: ((String) -> String?)? = null) : dev.harnessprotocol.conformance.RuntimeObservation {
    var streamResponse: ((com.sun.net.httpserver.HttpExchange, String) -> Boolean)? = null
    override val observedContexts: List<String> get() = requests
    override val observedTextValues: List<String> get() = textValues
    val requests = CopyOnWriteArrayList<String>()
    private val textValues = CopyOnWriteArrayList<String>()
    private val workers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${server.address.port}"
    @Volatile private var gate: CountDownLatch? = null
    override fun hold() { gate = CountDownLatch(1) }
    override fun release() { gate?.countDown() }
    suspend fun awaitRelease() { while (gate?.count == 1L) delay(5) }
    init {
        server.executor = workers
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                requests += body
                runCatching { collectStrings(Json.parseToJsonElement(body), textValues) }
                gate?.await(75, TimeUnit.SECONDS)
                if (streamResponse?.invoke(exchange, body) == true) return@createContext
                val response = overrideResponse?.invoke(body) ?: if (exchange.requestURI.path.contains("responses")) codexResponse() else geminiResponse()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } finally { exchange.close() }
        }
        server.start()
    }
    fun record(prompt: Prompt) {
        val values = prompt.messages.map { it.textContent() }
        requests += values.joinToString()
        textValues += values
    }

    private fun collectStrings(value: JsonElement, destination: MutableList<String>) {
        when (value) {
            is JsonObject -> value.values.forEach { collectStrings(it, destination) }
            is JsonArray -> value.forEach { collectStrings(it, destination) }
            is JsonPrimitive -> if (value.isString) destination += value.content
        }
    }
    private fun codexResponse(): String {
        val item = """{"id":"msg_native","type":"message","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"native-result","annotations":[]}]}"""
        return listOf(
            """{"type":"response.created","response":{"id":"resp_native","status":"in_progress","output":[]}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_native","type":"message","role":"assistant","phase":"final_answer","content":[]}}""",
            """{"type":"response.output_text.delta","item_id":"msg_native","output_index":0,"content_index":0,"delta":"native-result"}""",
            """{"type":"response.output_item.done","output_index":0,"item":$item}""",
            """{"type":"response.completed","response":{"id":"resp_native","status":"completed","output":[$item],"usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}""",
        ).joinToString("") { "data: $it\n\n" }
    }
    private fun geminiResponse(): String = "data: " + """{"candidates":[{"content":{"role":"model","parts":[{"text":"native-result"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":13},"modelVersion":"gemini-2.5-flash"}""" + "\n\n"
    override fun close() { release(); server.stop(0); workers.shutdownNow() }
}
