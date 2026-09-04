package experiment

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

/** Partial research adapter, not a production provider or a claim of complete AHP conformance.
 * Required approval applies to change_status in this configured harness. Questions need a future contract.
 */
class KoogHarness(
    private val store: ConversationStore,
    private val executorFactory: () -> PromptExecutor,
    private val operations: ReviewOperations,
    private val graceMillis: Long = 2000,
    private val iterations: Int = 20,
) : AgentHarness {
    override val provider = ProviderId("koog-experiment")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val sessions = mutableMapOf<SessionId, Session>()
    private var closed = false

    override fun validate(spec: AgentSpec) = CompatibilityReport(buildList {
        if (spec.model != null && spec.model != OpenAIModels.Chat.GPT4o.id)
            add(CompatibilityIssue("model", "This experiment has one configured model descriptor"))
        if (spec.workingDirectory != null) add(CompatibilityIssue("workingDirectory", "No working-directory mechanism configured"))
        if (spec.skills.isNotEmpty()) add(CompatibilityIssue("skills", "Skill loading is outside this experiment"))
        if (spec.executionPolicy.filesystem != FilesystemAccess.ProviderDefault)
            add(CompatibilityIssue("executionPolicy.filesystem", "No filesystem enforcement configured"))
        if (spec.executionPolicy.network != NetworkAccess.PROVIDER_DEFAULT)
            add(CompatibilityIssue("executionPolicy.network", "No network enforcement configured"))
        if (spec.executionPolicy.approval == ApprovalPolicy.AGENT_REVIEWED)
            add(CompatibilityIssue("executionPolicy.approval", "No automatic reviewer configured"))
    })

    override suspend fun createSession(spec: AgentSpec): AgentSession = synchronized(lock) {
        check(!closed)
        validate(spec).requireCompatible()
        val id = store.create()
        Session(id, spec).also { sessions[id] = it }
    }
    override suspend fun resumeSession(id: SessionId, spec: AgentSpec): AgentSession = synchronized(lock) {
        check(!closed)
        validate(spec).requireCompatible()
        check(id !in sessions) { "Release the existing handle before reopening" }
        store.load(id) // An unknown ID never creates a replacement conversation.
        Session(id, spec).also { sessions[id] = it }
    }

    private inner class Session(override val id: SessionId, override val spec: AgentSpec) : AgentSession {
        var released = false
        var active: Execution? = null
        override suspend fun execute(input: AgentInput): AgentExecution = synchronized(lock) {
            check(!closed && !released)
            check(active?.state?.value == null || active!!.state.value in TERMINAL) { "Execution already active" }
            Execution(id, spec, (input as AgentInput.Text).text, store.load(id)).also {
                active = it
                it.start()
            }
        }
        override suspend fun release() {
            val execution = synchronized(lock) {
                if (released) return
                released = true
                active
            }
            execution?.cancel()
            val stopped = withTimeoutOrNull(graceMillis) { execution?.join(); true } ?: false
            synchronized(lock) { sessions.remove(id) }
            if (!stopped) throw HarnessTransportException("Handle released; actual execution termination is unconfirmed")
        }
    }
    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            sessions.values.toList()
        }
        var unresolved: Throwable? = null
        runBlocking {
            owned.forEach { session ->
                try { session.release() } catch (e: HarnessTransportException) { unresolved = e }
            }
        }
        scope.cancel()
        unresolved?.let { throw it }
    }

    private inner class Execution(
        override val sessionId: SessionId,
        private val spec: AgentSpec,
        private val input: String,
        private val history: List<Message>,
    ) : AgentExecution {
        override val id = ExecutionId(UUID.randomUUID().toString())
        private val stateValue = MutableStateFlow(ExecutionState.STARTING)
        override val state = stateValue.asStateFlow()
        private val pendingValue = MutableStateFlow<List<InteractionRequest>>(emptyList())
        override val pendingInteractions = pendingValue.asStateFlow()
        private val observation = ObservationStream(id)
        override val events = observation.flow
        private val result = CompletableDeferred<AgentResult>()
        private val monitor = Any()
        private var pending: Pair<InteractionId, CompletableDeferred<ApprovalDecision>>? = null
        private var currentWork: WorkId? = null
        private val declined = mutableSetOf<WorkId>()
        private var usage: AgentUsage? = null
        private val job = scope.launch(start = CoroutineStart.LAZY) { run() }
        init {
            job.invokeOnCompletion { cause ->
                // A lazy job can be cancelled before its body enters the try/finally.
                if (!result.isCompleted && cause is CancellationException)
                    fail(ExecutionState.CANCELLED, AgentExecutionCancelledException())
                else if (!result.isCompleted && cause != null)
                    fail(ExecutionState.FAILED, AgentExecutionFailedException(FailureKind.PROVIDER, cause.message ?: "Runtime setup failed", cause))
            }
        }
        fun start() { job.start() }
        suspend fun join() { job.join() }
        private fun emit(event: AgentEvent) { observation.publish(event) }

        private suspend fun approval(): ApprovalDecision {
            if (spec.executionPolicy.approval != ApprovalPolicy.CALLER_DECIDES) return ApprovalDecision.DECLINE
            val interactionId = InteractionId(UUID.randomUUID().toString())
            val decision = CompletableDeferred<ApprovalDecision>()
            synchronized(monitor) {
                check(state.value !in TERMINAL)
                val request = InteractionRequest.Approval(
                    interactionId, currentWork, "Change the synthetic request status?", EffectKind.OTHER,
                    setOf(ApprovalDecision.APPROVE_ONCE, ApprovalDecision.DECLINE, ApprovalDecision.CANCEL),
                )
                pending = interactionId to decision
                pendingValue.value = listOf(request)
                stateValue.value = ExecutionState.WAITING
                emit(AgentEvent.InteractionRequested(id, request))
            }
            return decision.await()
        }
        override suspend fun respond(interactionId: InteractionId, response: InteractionResponse) = synchronized(monitor) {
            val open = pending ?: throw IllegalStateException("No open request")
            check(open.first == interactionId) { "Unknown or already resolved request" }
            val decision = (response as? InteractionResponse.Approval)?.decision
                ?: throw IllegalArgumentException("Wrong response type")
            val request = pendingValue.value.single() as InteractionRequest.Approval
            require(decision in request.availableDecisions)
            pending = null
            pendingValue.value = emptyList()
            stateValue.value = ExecutionState.RUNNING
            emit(AgentEvent.InteractionResolved(id, interactionId, InteractionResolution.Responded(response)))
            check(open.second.complete(decision))
        }
        private fun clearPending() {
            pending?.let { (requestId, decision) ->
                emit(AgentEvent.InteractionResolved(id, requestId, InteractionResolution.Cleared(ClearReason.TURN_INTERRUPTED)))
                decision.cancel()
            }
            pending = null
            pendingValue.value = emptyList()
            if (state.value == ExecutionState.WAITING) stateValue.value = ExecutionState.RUNNING
        }
        override suspend fun cancel() = synchronized(monitor) {
            if (state.value in TERMINAL) return@synchronized
            clearPending()
            job.cancel()
        }
        override suspend fun awaitResult(): AgentResult = result.await()

        private suspend fun run() {
            val executor = executorFactory()
            val guarded = object : ReviewOperations {
                override suspend fun lookup(requestId: String) = operations.lookup(requestId)
                override suspend fun changeStatus(requestId: String): String = when (approval()) {
                    ApprovalDecision.APPROVE_ONCE -> { currentCoroutineContext().ensureActive(); operations.changeStatus(requestId) }
                    ApprovalDecision.DECLINE -> { currentWork?.let { declined += it }; "declined" }
                    ApprovalDecision.CANCEL -> throw CancellationException("Caller cancelled the requested effect")
                    ApprovalDecision.APPROVE_FOR_SESSION -> error("Not offered")
                }
                override suspend fun askQuestion(question: String): String =
                    throw UnsupportedOperationException("AHP 0.1.0 has no question response contract")
            }
            val agent = reviewAgent(executor, guarded, history.filterNot { it is Message.System }, spec.instructions, iterations) {
                install(EventHandler) {
                    onAgentStarting {
                        stateValue.value = ExecutionState.RUNNING
                        emit(AgentEvent.ExecutionStarted(id))
                    }
                    onToolCallStarting {
                        val work = WorkId(it.toolCallId ?: UUID.randomUUID().toString())
                        currentWork = work
                        emit(AgentEvent.ToolCallChanged(id, work, it.toolName, WorkStatus.STARTED, it.toolArgs.toKotlinxJsonObject()))
                        if (it.toolName == "change_status") emit(AgentEvent.EffectChanged(id, work, EffectKind.OTHER, WorkStatus.STARTED, "Synthetic status change"))
                    }
                    onToolCallCompleted {
                        val work = checkNotNull(currentWork)
                        val status = if (work in declined) WorkStatus.DECLINED else WorkStatus.COMPLETED
                        emit(AgentEvent.ToolCallChanged(id, work, it.toolName, status, it.toolArgs.toKotlinxJsonObject(), it.toolResult?.toKotlinxJsonElement() ?: JsonNull))
                        if (it.toolName == "change_status") emit(AgentEvent.EffectChanged(id, work, EffectKind.OTHER, status, "Synthetic status change"))
                    }
                    onToolCallFailed {
                        currentWork?.let { work -> emit(AgentEvent.ToolCallChanged(id, work, it.toolName, WorkStatus.FAILED, error = it.message)) }
                    }
                    onLLMCallCompleted {
                        it.response?.metaInfo?.let { meta ->
                            if (meta.inputTokensCount != null || meta.outputTokensCount != null || meta.totalTokensCount != null) {
                                val next = AgentUsage(inputTokens = meta.inputTokensCount?.toLong(), outputTokens = meta.outputTokensCount?.toLong(), totalTokens = meta.totalTokensCount?.toLong())
                                usage = usage?.plus(next) ?: next
                                emit(AgentEvent.UsageChanged(id, checkNotNull(usage)))
                            }
                        }
                    }
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                val session = agent.createSession(id.value)
                val text = session.run(input)
                currentCoroutineContext().ensureActive()
                val messages = session.context().llm.readSession { prompt.messages }
                store.save(sessionId, messages)
                finish(AgentResult(text, usage = usage))
            } catch (e: CancellationException) {
                fail(ExecutionState.CANCELLED, AgentExecutionCancelledException())
            } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
                // A known native exception maps to the existing semantic stop reason.
                // No completed final message was produced; an empty finalMessage is allowed by AHP.
                finish(AgentResult("", StopReason.TURN_LIMIT, usage))
            } catch (e: Exception) {
                val kind = if (e is HarnessTransportException) FailureKind.TRANSPORT else FailureKind.PROVIDER
                fail(ExecutionState.FAILED, AgentExecutionFailedException(kind, e.message ?: "Koog execution failed", e))
            } finally {
                withContext(NonCancellable) { agent.close(); executor.close() }
            }
        }
        private fun finish(value: AgentResult) = synchronized(monitor) {
            clearPending()
            stateValue.value = ExecutionState.COMPLETED
            emit(AgentEvent.MessageCompleted(id, value.finalMessage))
            observation.publish(AgentEvent.ExecutionCompleted(id, value), last = true)
            result.complete(value)
        }
        private fun fail(terminal: ExecutionState, failure: AgentExecutionException) = synchronized(monitor) {
            clearPending()
            stateValue.value = terminal
            val event = if (failure is AgentExecutionCancelledException) AgentEvent.ExecutionCancelled(id)
                else AgentEvent.ExecutionFailed(id, (failure as AgentExecutionFailedException).kind, failure.message ?: "Failed")
            observation.publish(event, last = true)
            result.completeExceptionally(failure)
        }
    }
    companion object {
        private val TERMINAL = setOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED)
    }
}
