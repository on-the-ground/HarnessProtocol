package dev.harnessprotocol.integration

import ai.koog.agents.core.tools.*
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import dev.harnessprotocol.*
import dev.harnessprotocol.koog.KoogHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Serializable data class EffectArgs(val marker: String)

@Timeout(15)
class KoogNativeToolTest {
    @TempDir lateinit var directory: Path

    @Test fun `failed multi-call graph retains observed output and native context for the next task`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val prompts = mutableListOf<String>()
        val markerTool = object : SimpleTool<EffectArgs>(typeToken<EffectArgs>(), "remember_marker", "Remember the supplied marker") {
            override suspend fun execute(args: EffectArgs) = args.marker
        }
        val executor = object : PromptExecutor() {
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                prompts += prompt.messages.joinToString { it.textContent() }
                return when (calls.getAndIncrement()) {
                    0 -> Message.Assistant(listOf(MessagePart.Text("observed partial output"),
                        MessagePart.Tool.Call("native-call", "remember_marker", """{"marker":"retained-context"}""")), ResponseMetaInfo.Empty)
                    1 -> error("controlled model failure after a real tool call")
                    else -> Message.Assistant("recovered", ResponseMetaInfo.Empty)
                }
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
            override fun close() = Unit
        }
        KoogHarness({ executor }, OpenAIModels.Chat.GPT4o, ToolRegistry { tool(markerTool) }).use { harness ->
            val session = harness.createSession(SessionSpec())
            val outcome = withTimeout(5_000) { session.startTask(TaskRequest(TaskInput.Text("prior-input-marker"))).awaitOutcome() }
            assertIs<TaskOutcome.Failed>(outcome)
            val partial = assertIs<TaskOutput.Text>(outcome.output)
            assertEquals("observed partial output", partial.text)
            assertFalse(partial.complete)
            assertEquals(AgentUsage.Unknown, outcome.usage)
            assertIs<TaskOutcome.Completed>(withTimeout(5_000) {
                session.startTask(TaskRequest(TaskInput.Text("continue after failure"))).awaitOutcome()
            })
            assertTrue("prior-input-marker" in prompts.last())
            assertTrue("observed partial output" in prompts.last())
        }
    }

    @Test fun `real noncooperative Koog tool can finish its effect after an Unresolved handle`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val target = directory.resolve("effect.txt")
        val tool = object : SimpleTool<EffectArgs>(typeToken<EffectArgs>(), "write_fixture_marker", "Write a marker to the fixture resource") {
            override suspend fun execute(args: EffectArgs): String = withContext(NonCancellable) {
                entered.complete(Unit)
                proceed.await()
                Files.writeString(target, args.marker)
                finished.complete(Unit)
                "written"
            }
        }
        val executor = object : PromptExecutor() {
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                return if (calls.getAndIncrement() == 0)
                    Message.Assistant(MessagePartCompat.call(), ResponseMetaInfo.Empty)
                else Message.Assistant("done", ResponseMetaInfo.Empty)
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("not used")
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
            override fun close() = Unit
        }
        val harness = KoogHarness({ executor }, OpenAIModels.Chat.GPT4o, ToolRegistry { tool(tool) },
            cleanupBudget = CleanupBudget(100.milliseconds, 300.milliseconds, false))
        try {
            val session = harness.createSession(SessionSpec())
            val task = session.startTask(TaskRequest(TaskInput.Text("write the fixture marker")))
            withTimeout(5_000) { entered.await() }
            session.release()
            val outcome = task.awaitOutcome()
            assertIs<TaskOutcome.Unresolved>(outcome)
            assertFalse(Files.exists(target))
            assertFailsWith<SessionBlockedException> { session.startTask(TaskRequest(TaskInput.Text("overlap"))) }
            proceed.complete(Unit)
            withTimeout(5_000) { finished.await() }
            assertEquals("effect-after-release", Files.readString(target))
            assertEquals(outcome, task.awaitOutcome(), "late actual effects do not rewrite the settled handle")
        } finally { proceed.complete(Unit); harness.close() }
    }
}

private object MessagePartCompat {
    fun call() = ai.koog.prompt.message.MessagePart.Tool.Call("native-tool-1", "write_fixture_marker", """{"marker":"effect-after-release"}""")
}
