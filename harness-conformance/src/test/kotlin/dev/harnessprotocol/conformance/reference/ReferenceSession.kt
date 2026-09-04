package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.AgentTask
import dev.harnessprotocol.ApprovalScopeId
import dev.harnessprotocol.CompatibilityReport
import dev.harnessprotocol.CompatibilityStatus
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.IncompatibleRequirementException
import dev.harnessprotocol.PersistentSessionRef
import dev.harnessprotocol.RequirementUnconfirmedException
import dev.harnessprotocol.SessionBlockedException
import dev.harnessprotocol.SessionId
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.TaskId
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.TaskStartUnconfirmedException
import dev.harnessprotocol.UnconfirmedStart
import dev.harnessprotocol.WorkspaceRequirement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 참조 엔진의 [AgentSession] 구현. 문맥 직렬화, 차단 전파, session 승인 범위를 소유한다.
 *
 * 하나의 논리 session에 속한 모든 handle이 같은 차단을 본다는 계약(문맥 차단과 복구 범위)을
 * 재현하기 위해, 이 harness가 발급한 handle은 항상 같은 [ReferenceSession] 인스턴스를 공유한다
 * (harness 쪽에서 캐시).
 */
internal class ReferenceSession(
    override val id: SessionId,
    override val spec: SessionSpec,
    initialPersistentRef: PersistentSessionRef?,
    val harness: ReferenceHarness,
) : AgentSession {
    override var persistentRef: PersistentSessionRef? = initialPersistentRef
        private set

    fun assignPersistentRef(ref: PersistentSessionRef) {
        persistentRef = ref
    }

    private val mutex = Mutex()
    private var currentTask: ReferenceTask? = null
    private var blocked: SessionBlockedException? = null
    private var released = false
    private val taskSeq = AtomicInteger(0)
    private val approvedScopes = mutableMapOf<String, MutableSet<String>>()

    sealed interface StartMode {
        data object Accept : StartMode
        data class RejectBeforeDelivery(val message: String) : StartMode
        data class LoseAcknowledgement(val acceptedByRuntime: Boolean) : StartMode
    }

    @Volatile
    var nextStartOverride: StartMode? = null

    private var submissionCount = 0
    private var acceptedStartCount = 0

    fun observedSubmissions(): Int = submissionCount
    fun observedAcceptedStarts(): Int = acceptedStartCount

    fun grant(scopeId: ApprovalScopeId, targets: Set<String>) {
        approvedScopes.getOrPut(scopeId.value) { mutableSetOf() } += targets
    }

    fun hasGrant(scopeId: ApprovalScopeId, target: String): Boolean =
        approvedScopes[scopeId.value]?.contains(target) == true

    /** 이 session에 현재 진행 중이거나 정리 대상인 task. 없으면 null. */
    fun activeTask(): ReferenceTask? = currentTask?.takeIf { !it.isTerminal }

    override fun validate(request: TaskRequest): CompatibilityReport = harness.validateTaskRequest(spec, request)

    override suspend fun startTask(request: TaskRequest): AgentTask {
        var createdTask: ReferenceTask? = null
        var toThrowUnconfirmed: TaskStartUnconfirmedException? = null
        var toThrowReject: String? = null
        mutex.withLock {
            blocked?.let { throw it }
            if (released) throw IllegalStateException("session ${id.value} is released")
            currentTask?.let { if (!it.isTerminal) throw IllegalStateException("a task is already in progress on this session") }

            when (harness.startDecisionFor(spec, request)) {
                CompatibilityStatus.INCOMPATIBLE -> throw IncompatibleRequirementException(
                    listOf(dev.harnessprotocol.CompatibilityIssue("request", "profile declares this task request unsupported at the start boundary")),
                )
                CompatibilityStatus.UNCONFIRMED -> throw RequirementUnconfirmedException(
                    listOf(dev.harnessprotocol.CompatibilityIssue("request", "profile cannot confirm this task request at the start boundary")),
                )
                CompatibilityStatus.COMPATIBLE -> Unit
            }

            val mode = nextStartOverride ?: StartMode.Accept
            nextStartOverride = null

            when (mode) {
                is StartMode.RejectBeforeDelivery -> {
                    toThrowReject = mode.message
                    return@withLock
                }
                is StartMode.LoseAcknowledgement -> {
                    submissionCount++
                    val requestId = "req-${submissionCount}"
                    val created = newTask(request)
                    if (mode.acceptedByRuntime) {
                        acceptedStartCount++
                        currentTask = created
                        deliverContext(created)
                    }
                    blocked = SessionBlockedException(id, "previous start's acceptance was not confirmed")
                    toThrowUnconfirmed = TaskStartUnconfirmedException(
                        UnconfirmedStart(id, requestId),
                        "start request acceptance was not confirmed",
                    )
                    return@withLock
                }
                StartMode.Accept -> {
                    submissionCount++
                    acceptedStartCount++
                    val created = newTask(request)
                    currentTask = created
                    deliverContext(created)
                    createdTask = created
                }
            }
        }
        toThrowReject?.let { throw HarnessTransportException(it) }
        toThrowUnconfirmed?.let { throw it }
        return requireNotNull(createdTask) { "internal error: no task created and no rejection thrown" }
    }

    private fun newTask(request: TaskRequest): ReferenceTask {
        val taskId = TaskId("${id.value}:task:${taskSeq.incrementAndGet()}")
        return ReferenceTask(taskId, id, request, harness.provider, this, harness.scope)
    }

    private fun deliverContext(task: ReferenceTask) {
        val skills = (spec.requirements.workspace as? WorkspaceRequirement.Required)
            ?.skills
            ?.filter { it.activate }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        task.deliverToRuntime(spec.instructions, skills)
    }

    override suspend fun release() {
        val toSettle: ReferenceTask?
        mutex.withLock {
            if (released) return
            released = true
            toSettle = currentTask?.takeIf { !it.isTerminal }
        }
        toSettle?.let {
            harness.settleWithCleanupBudget(it, harness.cleanupBudget.perTask)
            if (it.outcome is TaskOutcome.Unresolved) {
                mutex.withLock { blocked = SessionBlockedException(id, "released while the last task was unresolved") }
            }
        }
    }

    /** harness.close()가 이 session의 미종결 task를 정리할 때 쓴다. */
    suspend fun settleForClose(budget: kotlin.time.Duration) {
        val toSettle = mutex.withLock { currentTask?.takeIf { !it.isTerminal } }
        toSettle?.let {
            harness.settleWithCleanupBudget(it, budget)
            if (it.outcome is TaskOutcome.Unresolved) {
                mutex.withLock { blocked = SessionBlockedException(id, "harness closed while the last task was unresolved") }
            }
        }
    }
}
