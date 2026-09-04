package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.AgentHarness
import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.Capability
import dev.harnessprotocol.CleanupBudget
import dev.harnessprotocol.CompatibilityIssue
import dev.harnessprotocol.CompatibilityIssueKind
import dev.harnessprotocol.CompatibilityReport
import dev.harnessprotocol.CompatibilityStatus
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.IncompatibleRequirementException
import dev.harnessprotocol.PersistentSessionRef
import dev.harnessprotocol.PersistentSessions
import dev.harnessprotocol.ProviderId
import dev.harnessprotocol.RequirementUnconfirmedException
import dev.harnessprotocol.SessionId
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.StorageNamespace
import dev.harnessprotocol.Support
import dev.harnessprotocol.SupportReport
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.UnresolvedReason
import dev.harnessprotocol.conformance.FixtureProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 참조 엔진의 [AgentHarness] 구현.
 *
 * 지원·거절·미확인 판정은 [profile]의 [FixtureProfile.cases]로만 결정한다 — 이 클래스 자체가
 * "일반적으로 무엇을 지원해야 하는가"를 임의로 정하지 않는다. 실제 세 adapter가 각자 다른
 * provider 규칙으로 같은 [dev.harnessprotocol.conformance.HarnessFixture] 계약을 채우는 것과
 * 같은 자리를 차지한다.
 */
internal open class ReferenceHarness(
    private val profile: FixtureProfile,
    val namespace: String,
    budget: CleanupBudget = DEFAULT_BUDGET,
) : AgentHarness {
    override val provider = ProviderId("reference")
    override val cleanupBudget: CleanupBudget = budget
    val scope: CoroutineScope = newScope()

    @Volatile
    private var mutableSupport: SupportReport = profile.expectedSupport
    override val support: SupportReport get() = mutableSupport

    internal val sessions = ConcurrentHashMap<String, ReferenceSession>()
    private val sessionSeq = AtomicInteger(0)

    @Volatile
    private var closed = false

    @Volatile
    var restarted: Boolean = false
        private set

    fun revoke(capability: Capability) {
        mutableSupport = SupportReport(mutableSupport.entries + (capability to Support.Unsupported("revoked at runtime by RuntimeControl")))
    }

    fun markRestarted() {
        restarted = true
    }

    override fun validate(spec: SessionSpec): CompatibilityReport =
        caseFor(spec)?.let { statusReport(it.sessionValidation) } ?: CompatibilityReport.Compatible

    fun validateTaskRequest(spec: SessionSpec, request: TaskRequest): CompatibilityReport =
        caseFor(spec, request)?.let { statusReport(it.taskValidation) } ?: CompatibilityReport.Compatible

    private fun createDecisionFor(spec: SessionSpec): CompatibilityStatus =
        caseFor(spec)?.createDecision ?: CompatibilityStatus.COMPATIBLE

    internal fun startDecisionFor(spec: SessionSpec, request: TaskRequest): CompatibilityStatus =
        caseFor(spec, request)?.startDecision ?: CompatibilityStatus.COMPATIBLE

    private fun caseFor(spec: SessionSpec, request: TaskRequest? = null) =
        profile.cases.firstOrNull { it.sessionSpec == spec && (request == null || it.request == request) }

    private fun statusReport(status: CompatibilityStatus): CompatibilityReport = when (status) {
        CompatibilityStatus.COMPATIBLE -> CompatibilityReport.Compatible
        CompatibilityStatus.INCOMPATIBLE -> CompatibilityReport(
            listOf(CompatibilityIssue("spec", "profile '${profile.id}' declares this requirement unsupported", CompatibilityIssueKind.UNSUPPORTED)),
        )
        CompatibilityStatus.UNCONFIRMED -> CompatibilityReport(
            listOf(CompatibilityIssue("spec", "profile '${profile.id}' cannot confirm this requirement", CompatibilityIssueKind.UNCONFIRMED)),
        )
    }

    override suspend fun createSession(spec: SessionSpec): AgentSession {
        if (closed) throw HarnessTransportException("harness is closed")
        val decision = createDecisionFor(spec)
        when (decision) {
            CompatibilityStatus.INCOMPATIBLE -> throw IncompatibleRequirementException(
                listOf(CompatibilityIssue("spec", "profile '${profile.id}' rejects this session at the create boundary")),
            )
            CompatibilityStatus.UNCONFIRMED -> throw RequirementUnconfirmedException(
                listOf(CompatibilityIssue("spec", "profile '${profile.id}' cannot confirm this session at the create boundary")),
            )
            CompatibilityStatus.COMPATIBLE -> Unit
        }
        val id = SessionId("${profile.id}:session:${sessionSeq.incrementAndGet()}")
        val session = ReferenceSession(id, spec, null, this)
        sessions[id.value] = session
        return session
    }

    /** 이 harness가 발급한 session이 unresolved task 뒤 새 startTask를 계속 거절하는지 확인용. */
    fun sessionOrNull(id: SessionId): ReferenceSession? = sessions[id.value]

    override fun close() {
        if (closed) return
        closed = true
        runBlocking {
            if (cleanupBudget.aggregatesAcrossResources) {
                val jobs = sessions.values.map { session -> async { session.settleForClose(cleanupBudget.perTask) } }
                withTimeoutOrNull(cleanupBudget.total) { jobs.awaitAll() }
            } else {
                sessions.values.forEach { session ->
                    withTimeoutOrNull(cleanupBudget.total) { session.settleForClose(cleanupBudget.perTask) }
                }
            }
            // 위 budget 안에서 확정하지 못한 task는 harness 정리 자체가 종료되므로 여기서 강제 정착시킨다.
            sessions.values.forEach { session ->
                session.activeTask()?.settleUnresolved(UnresolvedReason.CLEANUP_BOUND_EXCEEDED, "harness close exceeded the total cleanup budget")
            }
        }
        scope.cancel()
    }

    /** 취소를 요청하고 [budget] 안에서 종료를 확인하며, 확인하지 못하면 Unresolved로 정착시킨다. */
    suspend fun settleWithCleanupBudget(task: ReferenceTask, budget: Duration) {
        if (task.isTerminal) return
        task.requestCancellation()
        val outcome: TaskOutcome? = withTimeoutOrNull(budget) { task.awaitOutcome() }
        if (outcome == null && !task.isTerminal) {
            val reason = when {
                task.observationLost -> UnresolvedReason.OBSERVATION_LOST
                task.hasUncooperativeWork -> UnresolvedReason.CANCELLATION_UNCONFIRMED
                else -> UnresolvedReason.CLEANUP_BOUND_EXCEEDED
            }
            task.settleUnresolved(reason, "cancellation requested; no confirmation within cleanup budget")
        }
    }

    companion object {
        val DEFAULT_BUDGET = CleanupBudget(perTask = 200.milliseconds, total = 400.milliseconds, aggregatesAcrossResources = true)

        fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * [Capability.PERSISTENCE]를 [Support.Supported]로 선언한 profile에만 쓰는 harness.
 * [PersistentSessions]를 구현하지 않는 [ReferenceHarness]와 타입으로 구별된다.
 */
internal class PersistentReferenceHarness(
    profile: FixtureProfile,
    namespace: String,
    budget: CleanupBudget = DEFAULT_BUDGET,
) : ReferenceHarness(profile, namespace, budget), PersistentSessions {

    private var failNextWrite: String? = null
    private var failNextReopen: String? = null
    private var canonicalizeAs: PersistentSessionRef? = null

    fun failNextPersistenceWrite(message: String) {
        failNextWrite = message
    }

    fun failNextReopen(message: String) {
        failNextReopen = message
    }

    fun canonicalizeNextReopenAs(ref: PersistentSessionRef) {
        canonicalizeAs = ref
    }

    override suspend fun createSession(spec: SessionSpec): AgentSession {
        failNextWrite?.let {
            failNextWrite = null
            throw HarnessTransportException(it)
        }
        val session = super.createSession(spec)
        val id = SessionId(session.id.value)
        val ref = canonicalizeAs?.also { canonicalizeAs = null }
            ?: PersistentSessionRef(provider, StorageNamespace(namespace), id.value)
        Backstore.put(ref, PersistedContext(spec, id.value))
        val engineSession = sessionOrNull(id)!!
        engineSession.assignPersistentRef(ref)
        return engineSession
    }

    override suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec): AgentSession {
        failNextReopen?.let {
            failNextReopen = null
            throw HarnessTransportException(it)
        }
        Backstore.get(ref)
            ?: throw IncompatibleRequirementException(listOf(CompatibilityIssue("ref", "unknown persistent session reference")))
        val canonical = canonicalizeAs?.also { canonicalizeAs = null } ?: ref
        val id = SessionId(canonical.id)
        val reopened = ReferenceSession(id, spec, canonical, this)
        sessions[id.value] = reopened
        return reopened
    }
}

private data class PersistedContext(val spec: SessionSpec, val canonicalId: String)

/** harness 재생성을 흉내내기 위한, profile+namespace로 공유하는 in-memory backing store. */
private object Backstore {
    private val store = ConcurrentHashMap<String, PersistedContext>()

    private fun key(ref: PersistentSessionRef) = "${ref.provider.value}:${ref.namespace.value}:${ref.id}"

    fun put(ref: PersistentSessionRef, context: PersistedContext) {
        store[key(ref)] = context
    }

    fun get(ref: PersistentSessionRef): PersistedContext? = store[key(ref)]
}
