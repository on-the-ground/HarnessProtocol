package dev.harnessprotocol.integration

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.*
import ai.koog.prompt.streaming.StreamFrame
import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.ConfirmedSdkBridge
import dev.harnessprotocol.conformance.*
import dev.harnessprotocol.koog.KoogHarness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

abstract class NativeAccountingTest : HarnessAccountingConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun accountingFixture(): AccountingFixture = object : AccountingFixture {
        override val measurements = listOf(AgentUsage(inputTokens = 10, outputTokens = 3, totalTokens = 13),
            AgentUsage(inputTokens = 7, outputTokens = 2, totalTokens = 9), AgentUsage(inputTokens = 0, outputTokens = 0, totalTokens = 0))
        override val sessionMeasurements = if (factory == CodexNativeFactory) listOf(measurements[0],
            AgentUsage(inputTokens = 17, outputTokens = 5, totalTokens = 22), AgentUsage(inputTokens = 17, outputTokens = 5, totalTokens = 22))
        else listOf(null, null, null)
        private val calls = AtomicInteger()
        override val observation = ModelBoundary {
            val usage = measurements[calls.getAndIncrement().coerceAtMost(2)]
            if (factory == CodexNativeFactory) {
                val item = """{"id":"msg_account","type":"message","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"measured-result","annotations":[]}]}"""
                listOf(
                    """{"type":"response.created","response":{"id":"resp_account","status":"in_progress","output":[]}}""",
                    """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_account","type":"message","role":"assistant","phase":"final_answer","content":[]}}""",
                    """{"type":"response.output_text.delta","item_id":"msg_account","output_index":0,"content_index":0,"delta":"measured-result"}""",
                    """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                    """{"type":"response.completed","response":{"id":"resp_account","status":"completed","output":[$item],"usage":{"input_tokens":${usage.inputTokens},"output_tokens":${usage.outputTokens},"total_tokens":${usage.totalTokens}}}}""",
                ).joinToString("") { "data: $it\n\n" }
            } else "data: " + """{"candidates":[{"content":{"role":"model","parts":[{"text":"measured-result"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":${usage.inputTokens},"candidatesTokenCount":${usage.outputTokens},"totalTokenCount":${usage.totalTokens}}}""" + "\n\n"
        }
        override val spec = factory.spec()
        override val harness = if (factory == CodexNativeFactory) {
            val native = nativeBridge(factory, observation, directory)
            val replay = object : ConfirmedSdkBridge by native {
                override fun events(executionId: String) = native.events(executionId).transform { event ->
                    emit(event)
                    // Replay an actual cumulative native notification, without changing its values.
                    if (event["method"]?.jsonPrimitive?.contentOrNull == "thread/tokenUsage/updated") emit(event)
                }
            }
            processHarness(factory, replay, StorageNamespace(directory.toString()))
        } else if (factory != KoogNativeFactory) factory.create(observation, directory) else KoogHarness({ object : PromptExecutor() {
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                observation.requests += prompt.messages.joinToString { it.textContent() }
                observation.awaitRelease()
                val usage = measurements[calls.getAndIncrement().coerceAtMost(2)]
                return Message.Assistant("measured-result", ResponseMetaInfo.Empty.copy(inputTokensCount = usage.inputTokens?.toInt(),
                    outputTokensCount = usage.outputTokens?.toInt(), totalTokensCount = usage.totalTokens?.toInt()))
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
            override fun close() = Unit
        } }, OpenAIModels.Chat.GPT4o)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
class CodexNativeAccountingTest : NativeAccountingTest() { override val factory = CodexNativeFactory }
class GeminiNativeAccountingTest : NativeAccountingTest() { override val factory = GeminiNativeFactory }
class KoogNativeAccountingTest : NativeAccountingTest() { override val factory = KoogNativeFactory }
