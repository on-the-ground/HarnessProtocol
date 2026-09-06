package experiment

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/** Business boundary supplied by the fixture application; no AHP/Koog types. */
interface ReviewOperations {
    suspend fun lookup(requestId: String): String
    suspend fun changeStatus(requestId: String): String
    suspend fun askQuestion(question: String): String
}

@Serializable data class RequestArgs(val requestId: String)
@Serializable data class QuestionArgs(val question: String)
@Serializable data class ReviewOutcome(val requestId: String, val action: String, val reason: String)

class LookupTool(private val operations: ReviewOperations) : SimpleTool<RequestArgs>(
    typeToken<RequestArgs>(), "lookup_request", "Read a synthetic business request and its reference material",
) {
    override suspend fun execute(args: RequestArgs): String = operations.lookup(args.requestId)
}
class ChangeTool(private val operations: ReviewOperations) : SimpleTool<RequestArgs>(
    typeToken<RequestArgs>(), "change_status", "Change a synthetic request status after the configured approval gate",
) {
    override suspend fun execute(args: RequestArgs): String = operations.changeStatus(args.requestId)
}
class QuestionTool(private val operations: ReviewOperations) : SimpleTool<QuestionArgs>(
    typeToken<QuestionArgs>(), "ask_question", "Ask the caller for missing business information",
) {
    override suspend fun execute(args: QuestionArgs): String = operations.askQuestion(args.question)
}

/** Koog-native configuration. The graph/registry remain outside the application contract. */
fun reviewAgent(
    executor: PromptExecutor,
    operations: ReviewOperations,
    history: List<Message> = emptyList(),
    instructions: String? = null,
    iterations: Int = 20,
    features: GraphAIAgent.FeatureContext.() -> Unit = {},
): GraphAIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    agentConfig = AIAgentConfig(
        prompt = prompt("review") {
            system(instructions ?: "Review synthetic work requests. Use provided tools and explain the result.")
            messages(history)
        },
        // Model descriptor only: the deterministic executor performs no network requests.
        model = OpenAIModels.Chat.GPT4o,
        maxAgentIterations = iterations,
    ),
    strategy = singleRunStrategy(),
    toolRegistry = ToolRegistry { tool(LookupTool(operations)); tool(ChangeTool(operations)); tool(QuestionTool(operations)) },
    installFeatures = features,
)
