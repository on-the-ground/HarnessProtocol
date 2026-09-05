package experiment

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** Research configuration with real guarded tools and file history; uses the current public Port.
 * Its optional capabilities belong to this configuration, not to every bare Koog ToolRegistry.
 */
class KoogHarness(
    private val store: ConversationStore,
    private val executorFactory: () -> PromptExecutor,
    private val operations: ReviewOperations,
    graceMillis: Long = 2000,
    private val iterations: Int = 20,
) : AgentHarness, PersistentSessions {
    override val provider = ProviderId("koog-experiment")
    override val cleanupBudget = CleanupBudget(graceMillis.milliseconds, graceMillis.milliseconds, false)
    override val support = SupportReport(Capability.entries.associateWith {
        when (it) {
            Capability.CALLER_APPROVAL, Capability.QUESTIONS -> Support.Supported
            Capability.PERSISTENCE -> Support.Conditional(SupportScope.HARNESS, "File history with single-writer access in this application process")
            else -> Support.Unsupported("Not configured in this research adapter")
        }
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableMapOf<SessionId, Session>()
    private val lock = Any()
    private var closed = false
    override fun validate(spec: SessionSpec) = CompatibilityReport(buildList {
        if (spec.model != null && spec.model != OpenAIModels.Chat.GPT4o.id) add(CompatibilityIssue("model", "One configured model descriptor"))
        if (spec.requirements.workspace != WorkspaceRequirement.NotRequired) add(CompatibilityIssue("requirements.workspace", "No workspace loader"))
        if (spec.requirements.execution != ExecutionConstraint.ProviderDefault) add(CompatibilityIssue("requirements.execution", "No execution sandbox"))
        if (spec.requirements.retention != ContextRetentionRequirement.ProviderDefault) add(CompatibilityIssue("requirements.retention", "This research adapter persists conversation history"))
        if (spec.requirements.historyVisibility != UserHistoryVisibilityRequirement.ProviderDefault) add(CompatibilityIssue("requirements.historyVisibility", "No user-history visibility contract is configured"))
        if (spec.requirements.approval == ApprovalRequirement.AgentReviewed) add(CompatibilityIssue("requirements.approval", "No automatic reviewer"))
        if (spec.requirements.diagnostics != DiagnosticsRequirement.NotRequired) add(CompatibilityIssue("requirements.diagnostics", "No diagnostic stream configured"))
        val persistence = spec.requirements.persistence as? PersistenceRequirement.Required
        if (persistence?.acrossProcessRestart == true || persistence?.concurrentAccess == true)
            add(CompatibilityIssue("requirements.persistence", "Unresolved blocks and writer coordination are process-local"))
    })
    override suspend fun createSession(spec: SessionSpec): AgentSession = synchronized(lock) {
        check(!closed)
        validate(spec).requireCompatible()
        Session(store.create(), spec).also { sessions[it.id] = it }
    }
    override suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec): AgentSession = synchronized(lock) {
        check(!closed)
        require(ref.provider == provider && ref.namespace == store.namespace) { "Foreign persistent reference" }
        validate(spec).requireCompatible()
        val id = SessionId(ref.id)
        if (store.namespace to id in blocked) throw SessionBlockedException(id, "Prior work is unresolved")
        check(sessions[id]?.released != false) { "Release the existing handle before reopening" }
        store.load(id)
        Session(id, spec).also { sessions[id] = it }
    }
    private inner class Session(override val id: SessionId, override val spec: SessionSpec) : AgentSession {
        override val disposition = SessionDisposition(historyVisibility = UserHistoryVisibility.HIDDEN)
        override val persistentRef = if (spec.requirements.persistence is PersistenceRequirement.Required)
            PersistentSessionRef(provider, store.namespace, id.value) else null
        var released = false
        var active: ManagedTask? = null
        override fun validate(request: TaskRequest) = if (request.requirements.output == OutputRequirement.Text) CompatibilityReport.Compatible
            else CompatibilityReport(listOf(CompatibilityIssue("requirements.output", "No schema enforcement configured")))
        override suspend fun startTask(request: TaskRequest): AgentTask = synchronized(lock) {
            if (closed || released || store.namespace to id in blocked) throw SessionBlockedException(id, "Context is closed or unresolved")
            check(active?.isTerminal != false) { "A task is already active" }
            validate(request).requireCompatible()
            val history = store.load(id)
            val decisions = ConcurrentHashMap<InteractionId, CompletableDeferred<InteractionResponse>>()
            lateinit var job: Job
            val task = ManagedTask(TaskId(UUID.randomUUID().toString()), id, scope,
                cancelNative = { job.cancel() },
                respondNative = { interaction, response -> checkNotNull(decisions.remove(interaction)).complete(response); Unit },
                onTerminal = { if (it is TaskOutcome.Unresolved) blocked += store.namespace to id })
            job = scope.launch(start = CoroutineStart.LAZY) { run(task, request, history, decisions) }
            job.invokeOnCompletion { error ->
                if (!task.isTerminal) {
                    if (error is CancellationException) task.cancelled()
                    else task.failed(FailureKind.PROVIDER, error?.message ?: "Graph ended without outcome", error)
                }
            }
            active = task
            job.start()
            task
        }
        private suspend fun run(task: ManagedTask, request: TaskRequest, history: List<Message>, decisions: ConcurrentHashMap<InteractionId, CompletableDeferred<InteractionResponse>>) {
            var currentWork: WorkId? = null
            val declined = mutableSetOf<WorkId>()
            var usage = AgentUsage.Zero
            suspend fun ask(request: InteractionRequest): InteractionResponse {
                val answer = CompletableDeferred<InteractionResponse>()
                decisions[request.interactionId] = answer
                task.event(TaskEvent.InteractionRequested(task.id, request))
                return try { answer.await() } finally { decisions.remove(request.interactionId) }
            }
            val guarded = object : ReviewOperations {
                override suspend fun lookup(requestId: String) = operations.lookup(requestId)
                override suspend fun changeStatus(requestId: String): String {
                    val decision = if (spec.requirements.approval == ApprovalRequirement.CallerDecides) {
                        val request = InteractionRequest.Approval(InteractionId(UUID.randomUUID().toString()), currentWork,
                            "Change this synthetic request status?", EffectKind.OTHER,
                            setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE, ApprovalDecision.CANCEL))
                        (ask(request) as InteractionResponse.Approval).decision
                    } else ApprovalDecision.DECLINE
                    return when (decision) {
                        ApprovalDecision.APPROVE_ONCE -> { currentCoroutineContext().ensureActive(); operations.changeStatus(requestId) }
                        ApprovalDecision.DECLINE -> { currentWork?.let { declined += it }; "declined" }
                        ApprovalDecision.CANCEL -> throw CancellationException("Caller cancelled effect")
                        ApprovalDecision.APPROVE_FOR_SESSION -> error("Not offered without an enforceable grant")
                    }
                }
                override suspend fun askQuestion(question: String): String {
                    check(spec.requirements.questions == QuestionRequirement.CallerAnswers) { "Caller questions were not requested" }
                    return (ask(InteractionRequest.Question(InteractionId(UUID.randomUUID().toString()), currentWork,
                        question, allowsFreeForm = true)) as InteractionResponse.Answer).text
                }
            }
            var executor: PromptExecutor? = null
            var agent: ai.koog.agents.core.agent.GraphAIAgent<String, String>? = null
            var readHistory: (suspend () -> List<Message>)? = null
            var failure: Throwable? = null
            var reason = StopReason.FINISHED
            try {
                executor = executorFactory()
                agent = reviewAgent(executor, guarded, history.filterNot { it is Message.System }, spec.instructions, iterations) {
                    install(EventHandler) {
                        onAgentStarting { task.event(TaskEvent.TaskStarted(task.id)) }
                        onToolCallStarting {
                            val work = WorkId(it.toolCallId ?: UUID.randomUUID().toString()); currentWork = work
                            task.event(TaskEvent.ToolCallChanged(task.id, work, it.toolName, WorkStatus.STARTED, it.toolArgs.toKotlinxJsonObject().toString()))
                            if (it.toolName == "change_status") task.event(TaskEvent.EffectChanged(task.id, work, EffectKind.OTHER, WorkStatus.STARTED))
                        }
                        onToolCallCompleted {
                            val work = checkNotNull(currentWork)
                            val status = if (work in declined) WorkStatus.DECLINED else WorkStatus.COMPLETED
                            task.event(TaskEvent.ToolCallChanged(task.id, work, it.toolName, status, it.toolArgs.toKotlinxJsonObject().toString(), it.toolResult?.toKotlinxJsonElement()?.toString()))
                            if (it.toolName == "change_status") task.event(TaskEvent.EffectChanged(task.id, work, EffectKind.OTHER, status))
                        }
                        onToolCallFailed { currentWork?.let { work -> task.event(TaskEvent.ToolCallChanged(task.id, work, it.toolName, WorkStatus.FAILED, error = it.message)) } }
                        onLLMCallCompleted {
                            val meta = it.response?.metaInfo
                            usage += AgentUsage(inputTokens = meta?.inputTokensCount?.toLong(), outputTokens = meta?.outputTokensCount?.toLong(), totalTokens = meta?.totalTokensCount?.toLong())
                            task.event(TaskEvent.UsageChanged(task.id, usage))
                        }
                    }
                }
                val nativeSession = agent.createSession(task.id.value)
                readHistory = { nativeSession.context().llm.readSession { prompt.messages } }
                val text = nativeSession.run((request.input as TaskInput.Text).text)
                currentCoroutineContext().ensureActive()
                task.capture(TaskOutput.Text(text))
                task.event(TaskEvent.MessageCompleted(task.id, MessageId(UUID.randomUUID().toString()), text, MessageRole.ANSWER))
            } catch (limit: AIAgentMaxNumberOfIterationsReachedException) { reason = StopReason.ITERATION_LIMIT }
            catch (error: Throwable) { failure = error }
            finally {
                withContext(NonCancellable) {
                    try { readHistory?.invoke()?.let { if (!task.isTerminal) store.save(id, it) } }
                    catch (error: Throwable) { blocked += store.namespace to id; if (failure == null) failure = error }
                    try { agent?.close() } catch (error: Throwable) { if (failure == null) failure = error }
                    try { executor?.close() } catch (error: Throwable) { if (failure == null) failure = error }
                }
            }
            when (val error = failure) {
                null -> task.completed(reason)
                is CancellationException -> task.cancelled()
                else -> task.failed(FailureKind.PROVIDER, error.message ?: "Koog task failed", error)
            }
        }
        override suspend fun release() {
            val task = synchronized(lock) { if (released) return; released = true; active }
            withContext(NonCancellable) { settle(task) }
        }
    }
    private suspend fun settle(task: ManagedTask?) {
        if (task == null || task.isTerminal) return
        withTimeoutOrNull(cleanupBudget.total) { task.requestCancellation(); task.awaitOutcome() }
        if (!task.isTerminal) task.unresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "The real graph or tool has not terminated")
    }
    override fun close() {
        val owned = synchronized(lock) { if (closed) return; closed = true; sessions.values.toList() }
        runBlocking { coroutineScope { owned.map { async { it.release() } }.awaitAll() } }
        scope.cancel()
    }
    companion object { private val blocked = ConcurrentHashMap.newKeySet<Pair<StorageNamespace, SessionId>>() }
}
