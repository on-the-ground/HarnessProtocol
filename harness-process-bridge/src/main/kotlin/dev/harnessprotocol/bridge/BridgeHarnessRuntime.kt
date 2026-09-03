package dev.harnessprotocol.bridge

import dev.harnessprotocol.AgentEvent
import dev.harnessprotocol.ExecutionId
import dev.harnessprotocol.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-harness bookkeeping shared by the SDK adapters: the active-execution gate
 * of every session, session release, and settling everything on close.
 */
class BridgeHarnessRuntime(
    private val bridge: SdkBridge,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<SessionId, SessionState>()

    /** Registers (or re-registers after release) a session handle. */
    fun open(id: SessionId): SessionState = sessions.compute(id) { _, existing ->
        if (existing == null || existing.released.get()) SessionState(id) else existing
    }!!

    /**
     * Starts one execution under [session]'s gate.
     *
     * [start] performs the host request and returns the execution ID; the
     * request is not sent when the gate is held.
     *
     * @throws IllegalStateException when the session is released or another
     * execution of the same session is not terminal yet
     */
    suspend fun execute(
        session: SessionState,
        mapEvent: (ExecutionId) -> (JsonObject) -> List<AgentEvent>,
        start: suspend () -> ExecutionId,
    ): BridgeAgentExecution {
        check(!session.released.get()) { "session ${session.id.value} has been released" }
        check(session.gate.compareAndSet(null, STARTING)) {
            "session ${session.id.value} already has an active execution; wait for it to reach a terminal state"
        }
        val executionId = try {
            start()
        } catch (failure: Throwable) {
            session.gate.set(null)
            throw failure
        }
        val execution = BridgeAgentExecution(
            id = executionId,
            sessionId = session.id,
            source = bridge.events(executionId.value),
            bridge = bridge,
            scope = scope,
            mapEvent = mapEvent(executionId),
            onTerminal = { terminal -> session.gate.compareAndSet(terminal, null) },
        )
        // The gate holds the execution so release/close can settle it. If the
        // execution already settled before this swap, onTerminal's CAS missed
        // (the gate still held STARTING), so clear it here.
        session.gate.compareAndSet(STARTING, execution)
        if (execution.isTerminal) session.gate.compareAndSet(execution, null)
        return execution
    }

    /**
     * Releases a session's runtime resources: cancels its active execution with
     * a bounded wait, then asks the host to forget the session. Idempotent.
     */
    suspend fun release(session: SessionState, graceMillis: Long = DEFAULT_GRACE_MILLIS) {
        if (!session.released.compareAndSet(false, true)) return
        settleActive(session, graceMillis)
        sessions.remove(session.id, session)
        runCatching {
            bridge.request("release_session", buildJsonObject { put("sessionId", session.id.value) })
        }
    }

    /** Harness close: settle every active execution as cancelled and forget all sessions. */
    suspend fun closeAll(graceMillis: Long = DEFAULT_GRACE_MILLIS) {
        sessions.values.forEach { session ->
            session.released.set(true)
            settleActive(session, graceMillis)
        }
        sessions.clear()
    }

    private suspend fun settleActive(session: SessionState, graceMillis: Long) {
        val active = session.gate.get() as? BridgeAgentExecution ?: return
        if (active.isTerminal) return
        runCatching { active.cancel() }
        withTimeoutOrNull(graceMillis) { runCatching { active.awaitResult() } }
        active.settleCancelled()
    }

    class SessionState internal constructor(val id: SessionId) {
        internal val gate = AtomicReference<Any?>(null)
        internal val released = AtomicBoolean(false)
        val isReleased: Boolean get() = released.get()
    }

    private companion object {
        val STARTING = Any()
        const val DEFAULT_GRACE_MILLIS = 2_000L
    }
}
