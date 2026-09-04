package dev.harnessprotocol.conformance

import dev.harnessprotocol.AgentUsage
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.PersistentSessionRef
import dev.harnessprotocol.SessionBlockedException
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskStartUnconfirmedException
import dev.harnessprotocol.UnresolvedReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * close/release의 정리 상한, 비협조적 작업, 거짓 취소 검출, 문맥 차단·복구 범위, 종결 증거 규칙,
 * 시작 요청 acknowledgement 유실, 사용량 누적 의미를 검사한다.
 *
 * 각 `@Test`는 docs/testing.md "필수 공통 시나리오" 항목에 대응한다. 모든 검사는 harness를
 * `try`/`finally`로 닫는다 — 실패한 검사가 harness의 background scope를 흘려서 이후 검사의
 * 스케줄링을 굶기지 않도록 한다.
 */
abstract class HarnessConformanceCleanupTest : ConformanceTestSupport() {

    // -------------------------------------------------------------------------------- close/release 상한

    @Test
    fun `release settles an active task as cancelled when confirmation arrives within budget`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            launch(Dispatchers.Default) {
                waitUntil { task.state.value == dev.harnessprotocol.TaskState.RUNNING }
                control.reportCancelledTermination()
            }
            session.release()
            assertEquals(dev.harnessprotocol.TaskState.CANCELLED, task.state.value)
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Cancelled)
        } finally {
            h.close()
        }
    }

    @Test
    fun `release settles an unconfirmed task as Unresolved once the cleanup budget elapses`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            task.control().reportRunning()
            // nobody ever confirms cancellation: the fixture just stays silent.
            session.release()
            val outcome = withTimeout(3_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Unresolved, "expected Unresolved, got $outcome")
            assertEquals(dev.harnessprotocol.TaskState.UNRESOLVED, task.state.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a completion that arrives right after cleanup starts is still recovered within budget`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.scheduleCompletionAfter(30, OutputObservation.Text("just in time"))
            session.release() // perTask budget (200ms) comfortably covers the 30ms delay
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed, "a result that arrives within budget must not be discarded: $outcome")
        } finally {
            h.close()
        }
    }

    @Test
    fun `harness close settles active tasks across sessions within the total cleanup budget`(): Unit = runBlocking {
        val h = harnessFor()
        val sessionA = h.session()
        val sessionB = h.session()
        val taskA = sessionA.start("a")
        val taskB = sessionB.start("b")
        taskA.control().reportRunning()
        taskB.control().reportRunning()
        h.close() // neither task ever confirms; both must settle within the total budget, not hang.
        val outcomeA = withTimeout(2_000) { taskA.awaitOutcome() }
        val outcomeB = withTimeout(2_000) { taskB.awaitOutcome() }
        assertTrue(outcomeA is TaskOutcome.Unresolved || outcomeA is TaskOutcome.Cancelled)
        assertTrue(outcomeB is TaskOutcome.Unresolved || outcomeB is TaskOutcome.Cancelled)
    }

    // -------------------------------------------------------------------------- 비협조적 작업과 거짓 취소 검출

    @Test
    fun `uncooperative work yields Unresolved with a cancellation-unconfirmed reason, not a fabricated Cancelled`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.leaveUncooperativeWork("background-job")
            session.release()
            val outcome = withTimeout(3_000) { task.awaitOutcome() } as TaskOutcome.Unresolved
            assertEquals(UnresolvedReason.CANCELLATION_UNCONFIRMED, outcome.reason)
        } finally {
            h.close()
        }
    }

    @Test
    fun `false-cancel detection - work observed after Unresolved does not retroactively change the settled outcome`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.leaveUncooperativeWork("still-running")
            session.release()
            val outcome = withTimeout(3_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Unresolved)
            // the fixture now reveals the work actually kept going in the background — the already
            // -settled handle must not silently become Cancelled or Completed after the fact.
            control.releaseUncooperativeWork("still-running")
            assertEquals(outcome, task.awaitOutcome())
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------ 문맥 차단과 복구 범위

    @Test
    fun `a session stays blocked for new tasks after its last task was Unresolved, even after release`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            task.control().reportRunning()
            task.control().leaveUncooperativeWork("w")
            session.release()
            withTimeout(3_000) { task.awaitOutcome() }
            assertFailsWith<SessionBlockedException> { session.start("should be refused") }
        } finally {
            h.close()
        }
    }

    @Test
    fun `a brand-new session on the same harness is unaffected by another session's block`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val blocked = h.session()
            val task = blocked.start()
            task.control().reportRunning()
            task.control().leaveUncooperativeWork("w")
            blocked.release()
            withTimeout(3_000) { task.awaitOutcome() }
            assertFailsWith<SessionBlockedException> { blocked.start("refused") }

            val fresh = h.session()
            val freshTask = fresh.start("fine")
            freshTask.control().reportRunning()
            freshTask.control().reportCompletion()
            val outcome = withTimeout(2_000) { freshTask.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- 종결 증거 규칙

    @Test
    fun `ending only the inner turn does not terminate the task`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.endInnerTurnOnly()
            assertEquals(dev.harnessprotocol.TaskState.RUNNING, task.state.value, "an inner-turn boundary must not look terminal")
            control.reportCompletion()
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
        } finally {
            h.close()
        }
    }

    @Test
    fun `pure observation loss without a terminal report settles Unresolved with an observation-lost reason`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.dropObservationWithoutTerminal()
            session.release()
            val outcome = withTimeout(3_000) { task.awaitOutcome() } as TaskOutcome.Unresolved
            assertEquals(UnresolvedReason.OBSERVATION_LOST, outcome.reason)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a runtime that owned the work reports a confirmed transport failure, not Unresolved`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            task.control().reportRunning()
            fixture().control(h).killRuntime(ownsRunningWork = true)
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Failed)
            assertEquals(FailureKind.TRANSPORT, (outcome as TaskOutcome.Failed).kind)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a runtime that did not own the work leaves it outstanding rather than fabricating a result`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            task.control().reportRunning()
            fixture().control(h).killRuntime(ownsRunningWork = false)
            assertEquals(dev.harnessprotocol.TaskState.RUNNING, task.state.value, "work outside this process must not be declared terminal on its own")
            session.release()
            val outcome = withTimeout(3_000) { task.awaitOutcome() } as TaskOutcome.Unresolved
            assertEquals(UnresolvedReason.CANCELLATION_UNCONFIRMED, outcome.reason)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------ 시작 요청 acknowledgement 유실

    @Test
    fun `a start rejected before delivery does not block the session and can be retried`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            fixture().controlNextStart(session).rejectBeforeDelivery("refused before it reached the runtime")
            assertFailsWith<HarnessTransportException> { session.start("first try") }
            // a clean pre-delivery rejection is not ambiguous: the session must accept the retry.
            val task = session.start("retry")
            task.control().reportRunning()
            task.control().reportCompletion()
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
        } finally {
            h.close()
        }
    }

    @Test
    fun `losing the start acceptance acknowledgement blocks the session, distinct from a clean rejection`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            fixture().controlNextStart(session).loseAcceptanceAcknowledgement(acceptedByRuntime = true)
            assertFailsWith<TaskStartUnconfirmedException> { session.start("ambiguous") }
            // the caller never got a handle and must not start a new task on this context, because
            // whether the runtime actually accepted it is unknown.
            assertFailsWith<SessionBlockedException> { session.start("must not retry blindly") }
        } finally {
            h.close()
        }
    }

    // ---------------------------------------------------------------------------------- 사용량 누적 의미

    @Test
    fun `usage deltas accumulate and unknown fields stay unknown rather than becoming zero`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportUsageDelta(AgentUsage(inputTokens = 10, outputTokens = 5))
            control.reportUsageDelta(AgentUsage(inputTokens = 7)) // outputTokens unmeasured this turn
            control.reportCompletion()
            val outcome = withTimeout(2_000) { task.awaitOutcome() } as TaskOutcome.Completed
            assertEquals(17L, outcome.usage.inputTokens)
            assertEquals(null, outcome.usage.outputTokens, "an unmeasured segment must not silently become part of a known total")
        } finally {
            h.close()
        }
    }

    @Test
    fun `a fresh usage snapshot resets the baseline instead of double-counting prior deltas`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportUsageDelta(AgentUsage(inputTokens = 10))
            control.reportUsageSnapshot(AgentUsage(inputTokens = 100))
            control.reportUsageDelta(AgentUsage(inputTokens = 5))
            control.reportCompletion()
            val outcome = withTimeout(2_000) { task.awaitOutcome() } as TaskOutcome.Completed
            assertEquals(105L, outcome.usage.inputTokens)
        } finally {
            h.close()
        }
    }

    @Test
    fun `task and session usage are reported and preserved separately`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportUsageSnapshot(AgentUsage(inputTokens = 3), AgentUsage(inputTokens = 300))
            control.reportCompletion()
            val outcome = withTimeout(2_000) { task.awaitOutcome() } as TaskOutcome.Completed
            assertEquals(3L, outcome.usage.inputTokens)
            assertEquals(300L, outcome.sessionUsage?.inputTokens)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------- 확정 뒤 늦은 알림은 outcome을 덮지 않음

    @Test
    fun `a late notification after the outcome is confirmed does not overwrite it`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion(OutputObservation.Text("first and final"))
            val outcome = withTimeout(2_000) { task.awaitOutcome() }
            control.reportCancelledTermination() // arrives after confirmation; must be ignored
            assertEquals(outcome, task.awaitOutcome())
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- reopen ID 정규화

    @Test
    fun `reopening a persistent session surfaces the storage's canonical id, not the caller's raw string`(): Unit = runBlocking {
        val persistenceProfile = fixture().profiles().firstOrNull {
            it.expectedSupport[dev.harnessprotocol.Capability.PERSISTENCE] == dev.harnessprotocol.Support.Supported
        } ?: return@runBlocking // no profile declares persistence support; nothing to check here

        val h = fixture().createHarness(persistenceProfile.id)
        try {
            if (h !is dev.harnessprotocol.PersistentSessions) return@runBlocking
            val spec = persistenceProfile.cases.first().sessionSpec
            val session = h.createSession(spec)
            val ref = session.persistentRef
            assertNotNull(ref, "a session created under a persistence requirement must expose a persistent ref")

            val canonical = PersistentSessionRef(ref.provider, ref.namespace, "${ref.id}-canonical")
            fixture().control(session).canonicalizeNextReopenAs(canonical)
            val reopened = h.reopenSession(ref, spec)
            assertEquals(canonical.id, reopened.id.value, "reopen must surface the storage's canonical id, not blindly echo the caller's ref")
        } finally {
            h.close()
        }
    }
}
