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
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.measureTime
import kotlin.test.*

/**
 * All three adapters run their real native engine. Only the model boundary is deterministic:
 * App Server -> local Responses HTTP, Gemini SDK/core -> local generateContent HTTP,
 * Koog graph -> PromptExecutor. Assertions inspect actual model requests, not scripted answers.
 */
@Timeout(90)
abstract class NativeHarnessTest {
    @TempDir lateinit var directory: Path
    protected abstract fun harness(model: ModelBoundary): AgentHarness
    protected open fun spec() = SessionSpec(instructions = "AHP_NATIVE_INSTRUCTION", model = null)

    @Test fun `native task completes without an observer and preserves state and output`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            val task = h.createSession(spec()).startTask(TaskRequest(TaskInput.Text("first marker-alpha")))
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Completed>(outcome)
            assertEquals(TaskState.COMPLETED, task.state.value)
            assertEquals("native-result", (outcome.output as TaskOutput.Text).text)
            assertTrue(task.pendingInteractions.value.isEmpty())
            assertTrue(model.requests.any { "marker-alpha" in it }, "input must actually reach the model boundary")
            assertTrue(model.requests.any { "AHP_NATIVE_INSTRUCTION" in it }, "instructions must reach native model configuration")
        } }
    }

    @Test fun `same session carries prior native context while a new session stays isolated`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            withTimeout(60_000) { session.startTask(TaskRequest(TaskInput.Text("remember marker-alpha"))).awaitOutcome() }.also { assertIs<TaskOutcome.Completed>(it) }
            val firstCount = model.requests.size
            withTimeout(60_000) { session.startTask(TaskRequest(TaskInput.Text("followup marker-beta"))).awaitOutcome() }.also { assertIs<TaskOutcome.Completed>(it) }
            val subsequent = model.requests.drop(firstCount).joinToString()
            assertTrue("marker-alpha" in subsequent, "prior caller input must be in the actual subsequent model context")
            assertTrue("native-result" in subsequent, "prior assistant output must be in the actual subsequent model context")
            val nextCount = model.requests.size
            withTimeout(60_000) { h.createSession(spec()).startTask(TaskRequest(TaskInput.Text("independent marker-gamma"))).awaitOutcome() }
            val independent = model.requests.drop(nextCount).joinToString()
            assertTrue("marker-gamma" in independent)
            assertFalse("marker-alpha" in independent, "independent sessions must not inherit previous context")
        } }
    }

    @Test fun `unsupported task requirements are rejected before a native model call`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            val before = model.requests.size
            val request = TaskRequest(TaskInput.Text("structured"), TaskRequirements(OutputRequirement.Structured("{\"type\":\"object\"}")))
            assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(request).status)
            assertFailsWith<IncompatibleRequirementException> { session.startTask(request) }
            assertEquals(before, model.requests.size)
        } }
    }

    @Test fun `overlap is rejected and cancelling one waiter does not cancel native work`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            model.hold()
            val task = session.startTask(TaskRequest(TaskInput.Text("held native task")))
            withTimeout(60_000) { while (model.requests.none { "held native task" in it }) delay(10) }
            val first = async { task.awaitOutcome() }
            val second = async { task.awaitOutcome() }
            first.cancelAndJoin()
            assertFalse(task.state.value.isTerminal)
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("must not be sent"))) }
            assertTrue(model.requests.none { "must not be sent" in it })
            model.release()
            val outcome = withTimeout(60_000) { second.await() }
            assertIs<TaskOutcome.Completed>(outcome)
            assertEquals(outcome, task.awaitOutcome())
        } }
    }

    @Test fun `close bounds active native work and settles the waiter`() = runBlocking<Unit> {
        ModelBoundary().use { model ->
            val h = harness(model)
            try {
                val session = h.createSession(spec())
                model.hold()
                val task = session.startTask(TaskRequest(TaskInput.Text("hold until cleanup")))
                withTimeout(60_000) { while (model.requests.none { "hold until cleanup" in it }) delay(10) }
                val elapsed = measureTime { withContext(Dispatchers.IO) { h.close() } }
                assertTrue(elapsed.inWholeMilliseconds <= h.cleanupBudget.total.inWholeMilliseconds + 2_000, "close exceeded its declared budget: $elapsed")
                val outcome = withTimeout(2_000) { task.awaitOutcome() }
                assertTrue(outcome is TaskOutcome.Cancelled || outcome is TaskOutcome.Unresolved, "No model result was returned: $outcome")
                assertTrue(task.state.value.isTerminal)
                assertTrue(task.pendingInteractions.value.isEmpty())
            } finally { model.release(); h.close() }
        }
    }

    @Test fun `independent semantic and diagnostic observers finish and late subscription retains terminal`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            assertEquals(Support.Supported, h.support[Capability.DIAGNOSTICS])
            model.hold()
            val configured = spec().copy(requirements = SessionRequirements(diagnostics = DiagnosticsRequirement.Required))
            val task = h.createSession(configured).startTask(TaskRequest(TaskInput.Text("observe actual native work")))
            val semantic = async(start = CoroutineStart.UNDISPATCHED) { task.events.toList() }
            val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { (task as TaskDiagnostics).diagnostics.toList() }
            model.release()
            val outcome = withTimeout(60_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Completed>(outcome)
            val events = withTimeout(2_000) { semantic.await() }
            assertEquals(1, events.filterIsInstance<TaskEvent.Terminal>().size)
            assertIs<TaskEvent.TaskCompleted>(events.last())
            assertTrue(withTimeout(2_000) { diagnostic.await() }.any { it is ProviderDiagnostic })
            val late = withTimeout(2_000) { task.events.toList() }
            assertIs<TaskEvent.TaskCompleted>(late.last())
            // No assertion that terminal must be the first event: past replay is not required.
            task.requestCancellation()
            assertEquals(outcome, task.awaitOutcome())
        } }
    }

    @Test fun `explicit cancellation waits for native termination and leaves the session reusable`() = runBlocking<Unit> {
        ModelBoundary().use { model -> harness(model).use { h ->
            val session = h.createSession(spec())
            model.hold()
            val task = session.startTask(TaskRequest(TaskInput.Text("cancel native request")))
            withTimeout(60_000) { while (model.requests.none { "cancel native request" in it }) delay(10) }
            withTimeout(10_000) { task.requestCancellation() }
            val outcome = withTimeout(10_000) { task.awaitOutcome() }
            assertIs<TaskOutcome.Cancelled>(outcome)
            assertEquals(TaskState.CANCELLED, task.state.value)
            assertTrue(task.pendingInteractions.value.isEmpty())
            model.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                session.startTask(TaskRequest(TaskInput.Text("continue after confirmed cancellation"))).awaitOutcome()
            })
        } }
    }
}

abstract class NativePersistentHarnessTest : NativeHarnessTest() {
    protected open val supportsChangedInstructionsOnReopen = true
    @Test fun `persistent reopen preserves actual context and desired instructions across harness recreation`() = runBlocking<Unit> {
        ModelBoundary().use { model ->
            val configured = spec().copy(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
            val reference = harness(model).use { h ->
                val session = h.createSession(configured)
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                    session.startTask(TaskRequest(TaskInput.Text("durable marker-delta"))).awaitOutcome()
                })
                val ref = assertNotNull(session.persistentRef)
                session.release()
                ref
            }
            harness(model).use { h ->
                val before = model.requests.size
                val persistent = h as PersistentSessions
                val desired = configured.copy(instructions = "AHP_REOPEN_INSTRUCTION")
                if (!supportsChangedInstructionsOnReopen) {
                    assertFailsWith<IncompatibleRequirementException> { persistent.reopenSession(reference, desired) }
                    assertEquals(before, model.requests.size)
                }
                val reopened = persistent.reopenSession(reference, if (supportsChangedInstructionsOnReopen) desired else configured)
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) {
                    reopened.startTask(TaskRequest(TaskInput.Text("after harness recreation"))).awaitOutcome()
                })
                val actual = model.requests.drop(before).joinToString()
                assertTrue("marker-delta" in actual, "reopen must restore actual native context")
                if (supportsChangedInstructionsOnReopen)
                    assertTrue("AHP_REOPEN_INSTRUCTION" in actual, "desired instructions must reach the resumed model")
                assertFailsWith<IllegalArgumentException> {
                    h.reopenSession(reference.copy(namespace = StorageNamespace("foreign")), configured)
                }
                val unsupported = configured.copy(requirements = configured.requirements.copy(persistence = PersistenceRequirement.Required(acrossProcessRestart = true)))
                assertFailsWith<IncompatibleRequirementException> { h.createSession(unsupported) }
            }
        }
    }
}

class CodexNativeHarnessTest : NativePersistentHarnessTest() {
    override val supportsChangedInstructionsOnReopen = false
    override fun harness(model: ModelBoundary): AgentHarness {
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
        return CodexHarness.launch(CodexSdkOptions(pythonCommand = listOf(python), processWorkingDirectory = directory,
            environment = mapOf("CODEX_HOME" to home.toString())), storageNamespace = StorageNamespace(directory.toString()))
    }
}
class GeminiNativeHarnessTest : NativePersistentHarnessTest() {
    override fun spec() = SessionSpec(instructions = "AHP_NATIVE_INSTRUCTION", model = "gemini-2.5-flash")
    override fun harness(model: ModelBoundary): AgentHarness {
        val repo = Path.of(System.getProperty("ahp.repository"))
        val module = System.getenv("GEMINI_CLI_SDK_MODULE") ?: repo.resolve("_stage/gemini-cli-runtime/packages/sdk/dist/index.js").toString()
        check(Files.exists(Path.of(module))) { "Build the official Gemini SDK and set GEMINI_CLI_SDK_MODULE" }
        return GeminiCliHarness.launch(GeminiCliSdkOptions(sdkModule = module, processWorkingDirectory = directory,
            environment = mapOf("GOOGLE_GEMINI_BASE_URL" to model.url, "GEMINI_API_KEY" to "local-fixture-key", "GEMINI_TELEMETRY_ENABLED" to "false",
                "USERPROFILE" to Files.createDirectories(directory.resolve("gemini-home")).toString(), "HOME" to directory.resolve("gemini-home").toString())),
            storageNamespace = StorageNamespace(directory.toString()))
    }
}
class KoogNativeHarnessTest : NativeHarnessTest() {
    override fun harness(model: ModelBoundary): AgentHarness = KoogHarness({ object : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, modelDescriptor: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            model.requests += prompt.messages.joinToString { it.textContent() }
            model.awaitRelease()
            return Message.Assistant("native-result", ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Streaming is not used")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is not used")
        override fun close() = Unit
    } }, OpenAIModels.Chat.GPT4o)
}

/** Model protocol server, never a harness or Port implementation. No external model calls. */
class ModelBoundary : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    private val workers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${server.address.port}"
    @Volatile private var gate: CountDownLatch? = null
    fun hold() { gate = CountDownLatch(1) }
    fun release() { gate?.countDown() }
    suspend fun awaitRelease() { while (gate?.count == 1L) delay(5) }
    init {
        server.executor = workers
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                requests += body
                gate?.await(75, TimeUnit.SECONDS)
                val response = if (exchange.requestURI.path.contains("responses")) codexResponse() else geminiResponse()
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } finally { exchange.close() }
        }
        server.start()
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
