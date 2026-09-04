package dev.harnessprotocol.integration

import ai.koog.agents.core.tools.*
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.*
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.*
import dev.harnessprotocol.conformance.*
import dev.harnessprotocol.koog.KoogHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class CodexNativeOutcomeTest : HarnessOutcomeConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun outcomeFixture(output: OutputCase): OutcomeFixture = ProcessOutcomeFixture(CodexNativeFactory, directory, output)
}
class GeminiNativeOutcomeTest : HarnessOutcomeConformanceTest() {
    // The SDK rejects a response with no text/tool call and does not emit empty content events.
    override val missingResponseState = TaskState.FAILED
    override val emptyResponseState = TaskState.FAILED
    override val emptyTextReachesAdapter = false
    @TempDir lateinit var directory: Path
    override fun outcomeFixture(output: OutputCase): OutcomeFixture = ProcessOutcomeFixture(GeminiNativeFactory, directory, output)
}
class KoogNativeOutcomeTest : HarnessOutcomeConformanceTest() {
    // singleRunStrategy requires a response part; an empty parts list is a graph failure.
    override val missingResponseState = TaskState.FAILED
    override fun outcomeFixture(output: OutputCase): OutcomeFixture = KoogOutcomeFixture(output)
}

private class ProcessOutcomeFixture(factory: NativeHarnessFactory, directory: Path, output: OutputCase) : OutcomeFixture {
    private val begin = CountDownLatch(1)
    private val finish = CountDownLatch(1)
    @Volatile private var failed = false
    private val attempts = AtomicInteger()
    private val model = ModelBoundary().apply {
        streamResponse = { exchange, _ ->
            check(begin.await(75, TimeUnit.SECONDS))
            // A stream failure may make the real engine retry. Subsequent attempts fail before
            // output, so the fixture does not manufacture repeated partial text on each retry.
            if (attempts.getAndIncrement() > 0 && failed) {
                val body = """{"error":{"message":"Controlled invalid request","type":"invalid_request_error","code":"invalid_request","status":"INVALID_ARGUMENT"}}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.write(body)
                true
            } else {
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            fun send(value: String) { exchange.responseBody.write("data: $value\n\n".toByteArray()); exchange.responseBody.flush() }
            if (factory == CodexNativeFactory) {
                send("""{"type":"response.created","response":{"id":"resp_partial","status":"in_progress","output":[]}}""")
                if (output != OutputCase.MISSING) {
                    send("""{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_partial","type":"message","role":"assistant","phase":"final_answer","content":[]}}""")
                    send("""{"type":"response.output_text.delta","item_id":"msg_partial","output_index":0,"content_index":0,"delta":"${if (output == OutputCase.PARTIAL) "retained-partial" else ""}"}""")
                }
                if (output == OutputCase.PARTIAL) check(finish.await(75, TimeUnit.SECONDS))
                if (failed) send("""{"type":"error","code":"context_length_exceeded","message":"Controlled context overflow after partial output"}""")
                else {
                    val item = """{"id":"msg_partial","type":"message","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"${if (output == OutputCase.PARTIAL) "retained-partial" else ""}","annotations":[]}]}"""
                    if (output != OutputCase.MISSING) send("""{"type":"response.output_item.done","output_index":0,"item":$item}""")
                    send("""{"type":"response.completed","response":{"id":"resp_partial","status":"completed","output":[${if (output == OutputCase.MISSING) "" else item}]}}""")
                }
            } else {
                if (output != OutputCase.MISSING) send("""{"candidates":[{"content":{"role":"model","parts":[{"text":"${if (output == OutputCase.PARTIAL) "retained-partial" else ""}"}]},"index":0}]}""")
                if (output == OutputCase.PARTIAL) check(finish.await(75, TimeUnit.SECONDS))
                if (failed) send("""{"error":{"code":400,"message":"Controlled invalid request after partial output","status":"INVALID_ARGUMENT"}}""")
                else send("""{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP","index":0}]}""")
            }
            true
            }
        }
    }
    private val lost = CompletableDeferred<Unit>()
    private val delegate = nativeBridge(factory, model, directory)
    private val bridge = object : ConfirmedSdkBridge by delegate {
        override fun events(executionId: String): Flow<JsonObject> = channelFlow {
            val reader = launch { delegate.events(executionId).collect { send(it) }; close() }
            val loss = launch { lost.await(); reader.cancelAndJoin(); close() }
            reader.join()
            loss.cancelAndJoin()
        }
    }
    override val harness = processHarness(factory, bridge)
    override val spec = factory.spec()
    override fun beginModel() { begin.countDown() }
    override fun finishModel() { finish.countDown() }
    override fun failModel() { failed = true; finish.countDown() }
    override suspend fun loseObservation(session: AgentSession) { lost.complete(Unit) }
    override fun close() { beginModel(); finishModel(); try { harness.close() } finally { model.close() } }
}

private class KoogOutcomeFixture(private val output: OutputCase) : OutcomeFixture {
    private val begin = CompletableDeferred<Unit>()
    private val finish = CompletableDeferred<Unit>()
    @Volatile private var failed = false
    @Volatile private var noncooperative = false
    private val entered = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()
    private val tool = object : SimpleTool<EffectArgs>(typeToken<EffectArgs>(), "await_evidence", "Await the fixture continuation") {
        override suspend fun execute(args: EffectArgs): String {
            entered.complete(Unit)
            // Remain cancellable for confirmed cancellation; the explicit loss case switches to
            // noncooperative work before public release asks for cancellation.
            try { finish.await() } catch (cancel: CancellationException) {
                if (!noncooperative) throw cancel
                withContext(NonCancellable) { finish.await() }
            }
            return "continued"
        }
    }
    override val harness = KoogHarness({ object : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            begin.await()
            if (failed) error("Controlled model failure after partial output")
            return when (output) {
                OutputCase.MISSING -> Message.Assistant(emptyList<MessagePart.ResponsePart>(), ResponseMetaInfo.Empty)
                OutputCase.EMPTY -> Message.Assistant("", ResponseMetaInfo.Empty)
                OutputCase.PARTIAL -> if (calls.getAndIncrement() == 0) Message.Assistant(listOf(
                    MessagePart.Text("retained-partial"), MessagePart.Tool.Call("wait-partial", "await_evidence", """{"marker":"partial"}""")), ResponseMetaInfo.Empty)
                else Message.Assistant("retained-partial", ResponseMetaInfo.Empty)
            }
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    } }, OpenAIModels.Chat.GPT4o, ToolRegistry { tool(tool) }, cleanupBudget = CleanupBudget(100.milliseconds, 300.milliseconds, false))
    override val spec = SessionSpec()
    override fun beginModel() { begin.complete(Unit) }
    override fun finishModel() { finish.complete(Unit) }
    override fun failModel() { failed = true; finish.complete(Unit) }
    override suspend fun loseObservation(session: AgentSession) { withTimeout(5_000) { entered.await() }; noncooperative = true; session.release() }
    override fun close() { beginModel(); finishModel(); harness.close() }
}
