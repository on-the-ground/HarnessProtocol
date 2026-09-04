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
import kotlinx.coroutines.flow.Flow

class KoogNativeAccountingSequenceTest : HarnessAccountingSequenceConformanceTest() {
    override fun sequenceFixture(): AcceptanceFixture = object : AcceptanceFixture {
        override val observation = ModelBoundary()
        override val spec = SessionSpec()
        private val tool = object : SimpleTool<EffectArgs>(typeToken<EffectArgs>(), "accounting_step", "Return the marker") {
            override suspend fun execute(args: EffectArgs) = args.marker
        }
        override val harness = KoogHarness({ object : PromptExecutor() {
            private var calls = 0
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                observation.awaitRelease()
                return when (calls++) {
                    0 -> Message.Assistant(MessagePart.Tool.Call("measure-0", "accounting_step", """{"marker":"first"}"""),
                        ResponseMetaInfo.Empty.copy(inputTokensCount = 10, outputTokensCount = 5, totalTokensCount = 15))
                    1 -> Message.Assistant(MessagePart.Tool.Call("measure-1", "accounting_step", """{"marker":"second"}"""),
                        ResponseMetaInfo.Empty.copy(inputTokensCount = 7))
                    else -> Message.Assistant("measured-result", ResponseMetaInfo.Empty.copy(inputTokensCount = 0, outputTokensCount = 0, totalTokensCount = 0))
                }
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
            override fun close() = Unit
        } }, OpenAIModels.Chat.GPT4o, ToolRegistry { tool(tool) })
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
