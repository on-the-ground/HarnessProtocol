package dev.harnessprotocol.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * In-process adapter using real Koog graph execution and a caller-configured model executor.
 * A session retains observed native prompt history in memory. Durable storage, sandboxing,
 * and caller approval mediation are not supplied by a bare Koog ToolRegistry and are rejected.
 */
class KoogHarness(
    private val executorFactory: () -> PromptExecutor,
    private val model: LLModel,
    private val tools: ToolRegistry = ToolRegistry {},
    private val defaultInstructions: String = "Use the available tools to carry out the user's task.",
    private val maxIterations: Int = 20,
    override val cleanupBudget: CleanupBudget = CleanupBudget(2.seconds, 3.seconds, false),
) : AgentHarness {
    override val provider = ProviderId("koog")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<SessionId, Session>()
    override val support = SupportReport(Capability.entries.associateWith { capability ->
        when (capability) {
            Capability.DIAGNOSTICS -> Support.Supported
            else -> Support.Unsupported("Not supplied by this configured in-process Koog adapter")
        }
    })
    override fun validate(spec: SessionSpec) = CompatibilityReport(buildList {
        if (spec.model != null && spec.model != model.id) add(CompatibilityIssue("model", "Use the configured Koog model descriptor"))
        if (spec.requirements.approval != ApprovalRequirement.ProviderDefault) add(CompatibilityIssue("requirements.approval", "Bare tools have no common approval enforcement"))
        if (spec.requirements.questions != QuestionRequirement.NotRequired) add(CompatibilityIssue("requirements.questions", "No typed question tool is configured"))
        if (spec.requirements.persistence != PersistenceRequirement.NotRequired) add(CompatibilityIssue("requirements.persistence", "This adapter retains live session history only"))
        if (spec.requirements.workspace != WorkspaceRequirement.NotRequired) add(CompatibilityIssue("requirements.workspace", "No workspace or skill loader is configured"))
        if (spec.requirements.execution != ExecutionConstraint.ProviderDefault) add(CompatibilityIssue("requirements.execution", "Koog tools do not themselves establish a filesystem/network sandbox"))
    })
    override suspend fun createSession(spec: SessionSpec): AgentSession {
        check(!closed.get()) { "Harness is closed" }
        validate(spec).requireCompatible()
        val session = Session(SessionId(UUID.randomUUID().toString()), spec)
        sessions[session.id] = session
        return session
    }

    private inner class Session(override val id: SessionId, override val spec: SessionSpec) : AgentSession {
        override val persistentRef: PersistentSessionRef? = null
        private val lock = Any()
        private var history: List<Message> = emptyList()
        private var released = false
        private val blocked = AtomicBoolean(false)
        private var active: ManagedTask? = null
        override fun validate(request: TaskRequest) = CompatibilityReport(buildList {
            if (request.requirements.output != OutputRequirement.Text) add(CompatibilityIssue("requirements.output", "No schema-constrained graph is configured"))
        })

        override suspend fun startTask(request: TaskRequest): AgentTask = synchronized(lock) {
            if (closed.get() || released || blocked.get()) throw SessionBlockedException(id, "Session is closed or prior native work is unresolved")
            check(active?.isTerminal != false) { "A task is already active" }
            validate(request).requireCompatible()
            val taskId = TaskId(UUID.randomUUID().toString())
            lateinit var job: Job
            val task = ManagedTask(taskId, id, scope, cancelNative = { job.cancel() },
                respondNative = { _, _ -> error("This configuration exposes no interactions") },
                onTerminal = { outcome -> if (outcome is TaskOutcome.Unresolved) blocked.set(true) })
            val prior = history
            job = scope.launch(start = CoroutineStart.LAZY) { execute(task, request, prior) }
            job.invokeOnCompletion { failure ->
                if (!task.isTerminal) {
                    if (failure is CancellationException) task.cancelled()
                    else task.failed(FailureKind.PROVIDER, failure?.message ?: "Koog execution ended without an outcome", failure)
                }
            }
            active = task
            job.start()
            task
        }

        private suspend fun execute(task: ManagedTask, request: TaskRequest, prior: List<Message>) {
            var executor: PromptExecutor? = null
            var agent: ai.koog.agents.core.agent.GraphAIAgent<String, String>? = null
            var readHistory: (suspend () -> List<Message>)? = null
            var observedText = false
            var stopReason = StopReason.FINISHED
            var failure: Throwable? = null
            var usage = AgentUsage.Zero
            try {
                executor = executorFactory()
                agent = AIAgent(
                    promptExecutor = executor,
                    agentConfig = AIAgentConfig(prompt = prompt("ahp-session") {
                        system(spec.instructions ?: defaultInstructions)
                        messages(prior.filterNot { it is Message.System })
                    }, model = model, maxAgentIterations = maxIterations),
                    strategy = singleRunStrategy(),
                    toolRegistry = tools,
                ) {
                    install(EventHandler) {
                        onAgentStarting { task.event(TaskEvent.TaskStarted(task.id)) }
                        onToolCallStarting {
                            task.event(TaskEvent.ToolCallChanged(task.id, WorkId(it.toolCallId ?: UUID.randomUUID().toString()),
                                it.toolName, WorkStatus.STARTED, it.toolArgs.toKotlinxJsonObject().toString()))
                        }
                        onToolCallCompleted {
                            task.event(TaskEvent.ToolCallChanged(task.id, WorkId(it.toolCallId ?: UUID.randomUUID().toString()),
                                it.toolName, WorkStatus.COMPLETED, it.toolArgs.toKotlinxJsonObject().toString(), it.toolResult?.toKotlinxJsonElement()?.toString()))
                        }
                        onToolCallFailed {
                            task.event(TaskEvent.ToolCallChanged(task.id, WorkId(it.toolCallId ?: UUID.randomUUID().toString()), it.toolName, WorkStatus.FAILED, error = it.message))
                        }
                        onLLMCallCompleted {
                            val response = it.response
                            val text = response?.parts?.filterIsInstance<MessagePart.Text>()
                            if (!text.isNullOrEmpty()) {
                                observedText = true
                                val content = text.joinToString("\n") { part -> part.text }
                                task.capture(TaskOutput.Text(content, complete = false))
                                task.event(TaskEvent.MessageCompleted(task.id, MessageId(response.id ?: UUID.randomUUID().toString()), content, MessageRole.UNKNOWN))
                            }
                            response?.parts?.filterIsInstance<MessagePart.Reasoning>()?.forEach { reasoning ->
                                reasoning.summary?.let { summary -> task.event(TaskEvent.MessageCompleted(task.id,
                                    MessageId(reasoning.id ?: UUID.randomUUID().toString()), summary.joinToString("\n"), MessageRole.EXPLANATION)) }
                            }
                            val meta = it.response?.metaInfo
                            usage += AgentUsage(inputTokens = meta?.inputTokensCount?.toLong(), outputTokens = meta?.outputTokensCount?.toLong(), totalTokens = meta?.totalTokensCount?.toLong())
                            task.event(TaskEvent.UsageChanged(task.id, usage))
                            if (spec.requirements.diagnostics == DiagnosticsRequirement.Required)
                                task.diagnostic(ProviderDiagnostic(task.id, provider, "llm_call_completed", "usage=$usage"))
                        }
                    }
                }
                val nativeSession = agent.createSession(task.id.value)
                readHistory = { nativeSession.context().llm.readSession { prompt.messages } }
                val result = nativeSession.run((request.input as TaskInput.Text).text)
                currentCoroutineContext().ensureActive()
                if (observedText) {
                    task.capture(TaskOutput.Text(result))
                    task.event(TaskEvent.MessageCompleted(task.id, MessageId(UUID.randomUUID().toString()), result, MessageRole.ANSWER))
                }
            } catch (limit: AIAgentMaxNumberOfIterationsReachedException) {
                stopReason = StopReason.ITERATION_LIMIT
            } catch (error: Throwable) { failure = error }
            finally {
                // A cancelled coroutine is not termination evidence until its owned graph/tools and
                // cleanup have exited. A non-cooperative tool can outlive the public cleanup budget.
                withContext(NonCancellable) {
                    try {
                        readHistory?.invoke()?.let { messages -> synchronized(lock) { if (!task.isTerminal) history = messages } }
                    } catch (error: Throwable) {
                        // A context we cannot recover must not silently continue from stale history.
                        blocked.set(true)
                        if (failure == null) failure = error
                    }
                    try { agent?.close() } catch (error: Throwable) { if (failure == null) failure = error }
                    try { executor?.close() } catch (error: Throwable) { if (failure == null) failure = error }
                }
            }
            when (val error = failure) {
                null -> task.completed(stopReason)
                is CancellationException -> task.cancelled()
                else -> task.failed(FailureKind.PROVIDER, error.message ?: "Koog execution failed", error)
            }
        }

        suspend fun settle() {
            val task = synchronized(lock) { active } ?: return
            if (task.isTerminal) return
            withTimeoutOrNull(cleanupBudget.perTask) {
                task.requestCancellation()
                task.awaitOutcome()
            }
            if (!task.isTerminal) task.unresolved(UnresolvedReason.CANCELLATION_UNCONFIRMED, "Koog graph or tools have not confirmed termination")
        }
        override suspend fun release() {
            synchronized(lock) { if (released) return; released = true }
            withContext(NonCancellable) {
                withTimeoutOrNull(cleanupBudget.total) { settle() }
                active?.takeUnless { it.isTerminal }?.unresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "Session release reached its total bound")
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking {
            withTimeoutOrNull(cleanupBudget.total) { coroutineScope { sessions.values.map { async { it.release() } }.awaitAll() } }
        }
        scope.cancel()
    }
}
