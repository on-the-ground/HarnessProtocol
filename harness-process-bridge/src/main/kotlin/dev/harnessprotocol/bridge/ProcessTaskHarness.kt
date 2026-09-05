package dev.harnessprotocol.bridge

import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Native process adapter plumbing. EOF is observation loss, not proof that remote work stopped. */
abstract class ProcessTaskHarness(
    protected val bridge: SdkBridge,
    protected val scope: CoroutineScope,
    final override val cleanupBudget: CleanupBudget = CleanupBudget(2.seconds, 3.seconds, false),
    protected val storageNamespace: StorageNamespace? = null,
) : AgentHarness {
    private val closed = AtomicBoolean(false)
    private val lifecycle = Mutex()
    private val contexts = ConcurrentHashMap<SessionId, Context>()
    private val discardObligations = ConcurrentHashMap.newKeySet<SessionId>()
    protected abstract fun sessionPayload(spec: SessionSpec): JsonObject
    protected abstract fun ingest(task: ManagedTask, spec: SessionSpec, request: TaskRequest): (JsonObject) -> Unit
    protected open fun validateReopen(ref: PersistentSessionRef, spec: SessionSpec): CompatibilityReport = CompatibilityReport.Compatible
    protected open fun sessionOpened(id: SessionId, spec: SessionSpec, resumed: Boolean) = Unit

    protected fun persistenceIssues(spec: SessionSpec): List<CompatibilityIssue> {
        val requirement = spec.requirements.persistence as? PersistenceRequirement.Required ?: return emptyList()
        return buildList {
            if (storageNamespace == null) add(CompatibilityIssue("requirements.persistence", "Configure an explicit storage namespace before requiring persistence"))
            if (requirement.acrossProcessRestart) add(CompatibilityIssue("requirements.persistence.acrossProcessRestart", "Unresolved-context tombstones are retained in this application process only"))
            if (requirement.concurrentAccess) add(CompatibilityIssue("requirements.persistence.concurrentAccess", "Cross-harness/process coordination is not provided"))
        }
    }

    final override suspend fun createSession(spec: SessionSpec): AgentSession = lifecycle.withLock {
        check(!closed.get()) { "Harness is closed" }
        validate(spec).requireCompatible()
        val result = call("create_session", sessionPayload(spec))
        open(result, spec, false)
    }

    protected suspend fun reopen(ref: PersistentSessionRef, spec: SessionSpec): AgentSession = lifecycle.withLock {
        check(!closed.get()) { "Harness is closed" }
        require(ref.provider == provider && ref.namespace == storageNamespace) { "Foreign persistent session reference" }
        validate(spec).requireCompatible()
        validateReopen(ref, spec).requireCompatible()
        val existing = contexts.computeIfAbsent(SessionId(ref.id)) { Context(it) }
        existing.mutex.withLock {
            if (isBlocked(existing)) throw SessionBlockedException(existing.id, "Prior work on this context is unresolved")
            check(existing.active?.isTerminal != false) { "Context has an active task" }
            val result = call("resume_session", buildJsonObject { put("sessionId", ref.id); put("spec", sessionPayload(spec)) })
            open(result, spec, true)
        }
    }

    private suspend fun open(result: JsonObject, spec: SessionSpec, resumed: Boolean): AgentSession {
        val id = SessionId(result.getValue("sessionId").jsonPrimitive.content)
        val disposition = SessionDisposition(
            retention = when ((result["retention"] as? JsonPrimitive)?.contentOrNull) {
                "ephemeral" -> ContextRetentionDisposition.EPHEMERAL
                "materialized" -> ContextRetentionDisposition.MATERIALIZED
                else -> ContextRetentionDisposition.UNKNOWN
            },
            historyVisibility = when ((result["historyVisibility"] as? JsonPrimitive)?.contentOrNull) {
                "visible" -> UserHistoryVisibility.VISIBLE
                "hidden" -> UserHistoryVisibility.HIDDEN
                else -> UserHistoryVisibility.UNKNOWN
            },
        )
        val unconfirmed = buildList {
            if (spec.requirements.retention == ContextRetentionRequirement.Ephemeral &&
                disposition.retention != ContextRetentionDisposition.EPHEMERAL
            ) add(CompatibilityIssue(
                "requirements.retention",
                "Native session creation did not confirm ephemeral retention",
                CompatibilityIssueKind.UNCONFIRMED,
            ))
            if (spec.requirements.historyVisibility == UserHistoryVisibilityRequirement.Hidden &&
                disposition.historyVisibility != UserHistoryVisibility.HIDDEN
            ) add(CompatibilityIssue(
                "requirements.historyVisibility",
                "Native session creation did not confirm hidden user-history visibility",
                CompatibilityIssueKind.UNCONFIRMED,
            ))
        }
        if (unconfirmed.isNotEmpty()) {
            discardObligations += id
            val discardConfirmed = withContext(NonCancellable) {
                withTimeoutOrNull(cleanupBudget.total) {
                    runCatching {
                        bridge.confirmedRequest("discard_session", buildJsonObject { put("sessionId", id.value) })
                    }.isSuccess
                } ?: false
            }
            if (discardConfirmed) discardObligations -= id
            val issues = if (discardConfirmed) unconfirmed else unconfirmed + CompatibilityIssue(
                "session.discard",
                "Native session discard is unconfirmed; harness close will retry it",
                CompatibilityIssueKind.UNCONFIRMED,
            )
            throw RequirementUnconfirmedException(issues)
        }
        sessionOpened(id, spec, resumed)
        val context = contexts.computeIfAbsent(id) { Context(id) }
        val ref = if (spec.requirements.persistence is PersistenceRequirement.Required)
            PersistentSessionRef(provider, requireNotNull(storageNamespace), id.value) else null
        return Session(context, spec, disposition, ref)
    }

    private class Context(val id: SessionId) {
        val mutex = Mutex()
        val blocked = AtomicBoolean(false)
        @Volatile var active: ManagedTask? = null
    }

    private fun isBlocked(context: Context) = context.blocked.get() ||
        storageNamespace?.let { StoredContext(provider, it, context.id) in blockedContexts } == true

    private fun block(context: Context) {
        context.blocked.set(true)
        storageNamespace?.let { blockedContexts += StoredContext(provider, it, context.id) }
    }

    private inner class Session(
        private val context: Context,
        override val spec: SessionSpec,
        override val disposition: SessionDisposition,
        override val persistentRef: PersistentSessionRef?,
    ) : AgentSession {
        override val id get() = context.id
        private val released = AtomicBoolean(false)
        override fun validate(request: TaskRequest): CompatibilityReport = CompatibilityReport(buildList {
            if (request.requirements.output is OutputRequirement.Structured)
                add(CompatibilityIssue("requirements.output", "This native connection does not yet enforce an output schema"))
        })

        override suspend fun startTask(request: TaskRequest): AgentTask = context.mutex.withLock {
            if (closed.get() || released.get() || isBlocked(context)) throw SessionBlockedException(id, "Session is closed or its context is unresolved")
            check(context.active?.isTerminal != false) { "A task is already active on this session" }
            validate(request).requireCompatible()
            val requestId = UUID.randomUUID().toString()
            val result = try {
                withTimeout(30.seconds) {
                    bridge.confirmedRequest("start_execution", buildJsonObject {
                        put("sessionId", id.value)
                        put("requestId", requestId)
                        put("input", buildJsonObject { put("type", "text"); put("text", (request.input as TaskInput.Text).text) })
                    })
                }
            } catch (failure: Throwable) {
                if (failure is BridgeNotDeliveredException) throw HarnessTransportException("Start was not delivered", failure)
                block(context)
                throw TaskStartUnconfirmedException(UnconfirmedStart(id, requestId), "Native start acceptance is unconfirmed", failure)
            }
            val taskId = try { TaskId(result.getValue("executionId").jsonPrimitive.content) }
            catch (failure: Exception) {
                block(context)
                throw TaskStartUnconfirmedException(UnconfirmedStart(id, requestId), "Native start returned no usable handle", failure)
            }
            val task = ManagedTask(taskId, id, scope,
                cancelNative = { call("cancel_execution", buildJsonObject { put("executionId", taskId.value) }) },
                respondNative = { interactionId, response ->
                    try {
                        withTimeout(30.seconds) {
                            bridge.confirmedRequest("respond_interaction", buildJsonObject {
                                put("executionId", taskId.value); put("interactionId", interactionId.value)
                                put("response", when (response) {
                                    is InteractionResponse.Approval -> buildJsonObject { put("decision", response.decision.name.lowercase()) }
                                    is InteractionResponse.Answer -> buildJsonObject { put("text", response.text) }
                                })
                            })
                        }
                    } catch (failure: BridgeNotDeliveredException) { throw HarnessTransportException("Response was not delivered", failure) }
                },
                onTerminal = { outcome ->
                    if (outcome is TaskOutcome.Unresolved) block(context)
                    bridge.release(taskId.value)
                },
            )
            context.active = task
            val mapper = ingest(task, spec, request)
            scope.launch {
                try {
                    bridge.events(taskId.value).collect { if (!task.isTerminal) mapper(it) }
                    if (!task.isTerminal) task.unresolved(UnresolvedReason.OBSERVATION_LOST, "Native observation ended without task termination evidence")
                } catch (failure: Throwable) {
                    if (!task.isTerminal) task.unresolved(UnresolvedReason.OBSERVATION_LOST, failure.message ?: "Native observation failed")
                }
            }
            if (closed.get() || released.get()) task.unresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "Start completed during handle cleanup")
            task
        }

        override suspend fun release() {
            if (!released.compareAndSet(false, true)) return
            val ephemeral = spec.requirements.retention == ContextRetentionRequirement.Ephemeral
            if (ephemeral) discardObligations += id
            withContext(NonCancellable) {
                withTimeoutOrNull(cleanupBudget.total) {
                    context.mutex.withLock {
                        settle(context.active)
                        val releaseConfirmed = runCatching {
                            call("release_session", buildJsonObject { put("sessionId", id.value) })
                        }.isSuccess
                        if (releaseConfirmed && ephemeral) discardObligations -= id
                    }
                }
                context.active?.takeUnless { it.isTerminal }?.unresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "Session release reached its time bound")
            }
        }
    }

    private suspend fun settle(task: ManagedTask?) {
        if (task == null || task.isTerminal) return
        withTimeoutOrNull(cleanupBudget.perTask) {
            coroutineScope {
                launch { runCatching { task.requestCancellation() } }
                task.awaitOutcome()
            }
        }
        if (!task.isTerminal) task.unresolved(UnresolvedReason.CANCELLATION_UNCONFIRMED, "Cancellation did not yield native termination evidence within the cleanup budget")
    }

    protected suspend fun call(method: String, params: JsonObject): JsonObject = try {
        withTimeout(30.seconds) { bridge.confirmedRequest(method, params) }
    } catch (failure: Exception) { throw HarnessTransportException("Native $method call failed", failure) }

    final override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking {
            withTimeoutOrNull(cleanupBudget.total) {
                lifecycle.withLock {
                    coroutineScope {
                        val activeCleanup = contexts.values.map { async { settle(it.active) } }
                        val obligationCleanup = discardObligations.toList().map { id -> async {
                            if (runCatching {
                                bridge.confirmedRequest("discard_session", buildJsonObject { put("sessionId", id.value) })
                            }.isSuccess) discardObligations -= id
                        } }
                        activeCleanup.awaitAll()
                        obligationCleanup.awaitAll()
                    }
                }
            }
            contexts.values.forEach { context ->
                context.active?.takeUnless { it.isTerminal }?.unresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "Harness close reached its total bound")
            }
        }
        bridge.close()
        scope.cancel()
    }

    private data class StoredContext(val provider: ProviderId, val namespace: StorageNamespace, val id: SessionId)
    private companion object {
        // Native history survives harness recreation; its unresolved block must survive too.
        // Durable tombstones and recovery across the application process are not implemented.
        val blockedContexts = ConcurrentHashMap.newKeySet<StoredContext>()
    }
}
