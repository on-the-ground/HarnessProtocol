package experiment

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.*
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Only the model boundary is scripted. Koog graph, registry, tool invocation and hooks are real. */
class ScriptedExecutor(vararg steps: suspend (Prompt) -> Message.Assistant) : PromptExecutor() {
    private val steps = ConcurrentLinkedQueue(steps.toList())
    val prompts = CopyOnWriteArrayList<Prompt>()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        check(prompts.size <= 128) { "Fixture model call cap exceeded" }
        return checkNotNull(steps.poll()) { "Unexpected model request" }(prompt)
    }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("Streaming is outside this fixture")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Not used")
    override fun close() = Unit
}
fun answer(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
fun call(name: String, args: String, id: String = "call-1") =
    Message.Assistant(MessagePart.Tool.Call(id, name, args), ResponseMetaInfo.Empty)
fun lookupCall() = call("lookup_request", """{"requestId":"R-1"}""")
fun changeCall() = call("change_status", """{"requestId":"R-1"}""", "change-1")

open class FixtureOperations : ReviewOperations {
    val reads = AtomicInteger()
    val changes = AtomicInteger()
    val lookupEntered = CompletableDeferred<Unit>()
    var lookupGate: CompletableDeferred<Unit>? = null
    override suspend fun lookup(requestId: String): String {
        reads.incrementAndGet()
        lookupEntered.complete(Unit)
        lookupGate?.await()
        return "request=$requestId; category=documentation; reference=policy-A"
    }
    override suspend fun changeStatus(requestId: String): String {
        changes.incrementAndGet()
        return "request=$requestId; status=reviewed"
    }
    override suspend fun askQuestion(question: String): String = "scope=internal"
}
