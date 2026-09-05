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
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.Path
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

    /** Validate and materialize local workspace resources before a native session is allocated. */
    protected fun workspaceIssues(spec: SessionSpec): List<CompatibilityIssue> {
        val workspace = spec.requirements.workspace as? WorkspaceRequirement.Required ?: return emptyList()
        return buildList {
            workspace.workingDirectory?.let { value ->
                val path = runCatching { Path.of(value) }.getOrNull()
                if (path == null || !Files.isDirectory(path))
                    add(CompatibilityIssue("requirements.workspace.workingDirectory", "Working directory must be an existing directory"))
            }
            workspace.skills.forEachIndexed { index, skill ->
                val directory = resolveSkillDirectory(workspace, skill)
                if (!Files.isDirectory(directory) || !Files.isRegularFile(directory.resolve("SKILL.md")))
                    add(CompatibilityIssue("requirements.workspace.skills[$index].path", "Skill path must be a directory containing SKILL.md"))
            }
        }
    }

    /**
     * Applies active skill instructions at the native instruction level. Inactive skills are made
     * discoverable by name and path without leaking their bodies into the model context.
     */
    protected fun effectiveInstructions(spec: SessionSpec): String? {
        val workspace = spec.requirements.workspace as? WorkspaceRequirement.Required
        if (workspace == null || workspace.skills.isEmpty()) return spec.instructions
        val catalog = workspace.skills.joinToString("\n") { skill ->
            "- ${skill.name}: ${resolveSkillDirectory(workspace, skill)}"
        }
        val active = workspace.skills.filter { it.activate }.joinToString("\n\n") { skill ->
            val directory = resolveSkillDirectory(workspace, skill)
            "Active skill '${skill.name}' (base directory: $directory):\n${Files.readString(directory.resolve("SKILL.md"))}"
        }
        return buildList {
            spec.instructions?.let(::add)
            add("Available skills (do not apply an inactive skill unless the task explicitly activates it):\n$catalog")
            if (active.isNotEmpty()) add(active)
        }.joinToString("\n\n")
    }

    private fun resolveSkillDirectory(workspace: WorkspaceRequirement.Required, skill: SkillReference): Path {
        val raw = Path.of(skill.path)
        if (raw.isAbsolute) return raw.normalize()
        return workspace.workingDirectory?.let { Path.of(it).resolve(raw).normalize() } ?: raw.toAbsolutePath().normalize()
    }

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
            if (existing.handles.get() > 0 && existing.spec != spec) {
                CompatibilityReport(listOf(CompatibilityIssue("spec",
                    "Release existing handles before changing the shared native context configuration"))).requireCompatible()
            }
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
        context.spec = spec
        context.handles.incrementAndGet()
        val ref = if (spec.requirements.persistence is PersistenceRequirement.Required)
            PersistentSessionRef(provider, requireNotNull(storageNamespace), id.value) else null
        return Session(context, spec, disposition, ref)
    }

    private class Context(val id: SessionId) {
        val mutex = Mutex()
        val blocked = AtomicBoolean(false)
        val handles = AtomicInteger()
        @Volatile var spec: SessionSpec? = null
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
            val remainingHandles = context.handles.decrementAndGet()
            if (ephemeral && remainingHandles == 0) discardObligations += id
            withContext(NonCancellable) {
                withTimeoutOrNull(cleanupBudget.total) {
                    lifecycle.withLock {
                        if (!closed.get()) {
                            context.mutex.withLock {
                                settle(context.active)
                                // Reopened handles share a native context. Releasing one handle must not
                                // discard the context still owned by another live handle.
                                if (remainingHandles == 0) {
                                    val releaseConfirmed = runCatching {
                                        call("release_session", buildJsonObject { put("sessionId", id.value) })
                                    }.isSuccess
                                    if (releaseConfirmed && ephemeral) discardObligations -= id
                                }
                            }
                        }
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
