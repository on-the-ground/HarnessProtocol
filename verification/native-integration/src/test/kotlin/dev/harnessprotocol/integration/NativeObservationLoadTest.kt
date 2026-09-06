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
import dev.harnessprotocol.conformance.*
import dev.harnessprotocol.koog.KoogHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

abstract class NativeObservationLoadTest : HarnessObservationLoadConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun loadFixture(): AcceptanceFixture = object : AcceptanceFixture {
        override val observation = ModelBoundary().apply {
            streamResponse = { exchange, _ ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                fun send(value: String) { exchange.responseBody.write("data: $value\n\n".toByteArray()); exchange.responseBody.flush() }
                if (factory == CodexNativeFactory) {
                    send("""{"type":"response.created","response":{"id":"resp_load","status":"in_progress","output":[]}}""")
                    send("""{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_load","type":"message","role":"assistant","phase":"final_answer","content":[]}}""")
                    repeat(700) { send("""{"type":"response.output_text.delta","item_id":"msg_load","output_index":0,"content_index":0,"delta":"x"}"""); Thread.sleep(2) }
                    val item = """{"id":"msg_load","type":"message","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"load-complete","annotations":[]}]}"""
                    send("""{"type":"response.output_item.done","output_index":0,"item":$item}""")
                    send("""{"type":"response.completed","response":{"id":"resp_load","status":"completed","output":[$item]}}""")
                } else {
                    // Separate completed inner model responses are not required for token streaming.
                    repeat(700) { index -> send("""{"candidates":[{"content":{"role":"model","parts":[{"text":"chunk-$index;"}]},"index":0}]}"""); Thread.sleep(2) }
                    send("""{"candidates":[{"content":{"role":"model","parts":[{"text":"load-complete"}]},"finishReason":"STOP","index":0}]}""")
                }
                true
            }
        }
        override val spec = factory.spec().copy(requirements = SessionRequirements(diagnostics = DiagnosticsRequirement.Required))
        override val harness = if (factory == KoogNativeFactory) loadKoog(observation) else factory.create(observation, directory)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
class CodexNativeObservationLoadTest : NativeObservationLoadTest() { override val factory = CodexNativeFactory }
class GeminiNativeObservationLoadTest : NativeObservationLoadTest() { override val factory = GeminiNativeFactory }
class KoogNativeObservationLoadTest : NativeObservationLoadTest() { override val factory = KoogNativeFactory }

private fun loadKoog(boundary: ModelBoundary): AgentHarness {
    val tool = object : SimpleTool<EffectArgs>(typeToken<EffectArgs>(), "load_step", "Return the marker") {
        override suspend fun execute(args: EffectArgs) = args.marker
    }
    return KoogHarness({ object : PromptExecutor() {
        private val calls = AtomicInteger()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            boundary.awaitRelease()
            delay(2)
            val call = calls.getAndIncrement()
            // On the tool-result edge singleRunStrategy prioritizes text termination. Keep
            // intermediate responses tool-only so this is a real multi-step native graph.
            return if (call < 400) Message.Assistant(
                MessagePart.Tool.Call("load-$call", "load_step", """{"marker":"$call"}"""), ResponseMetaInfo.Empty)
            else Message.Assistant("load-complete", ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    } }, OpenAIModels.Chat.GPT4o, ToolRegistry { tool(tool) }, maxIterations = 10_000)
}
