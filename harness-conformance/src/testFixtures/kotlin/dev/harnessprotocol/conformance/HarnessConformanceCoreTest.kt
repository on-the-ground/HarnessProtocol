package dev.harnessprotocol.conformance

// Reusable scenarios; only concrete native adapter bindings count as executed tests.

import dev.harnessprotocol.AgentUsage
import dev.harnessprotocol.FailureKind
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.IncompatibleRequirementException
import dev.harnessprotocol.RequirementUnconfirmedException
import dev.harnessprotocol.SchemaValidation
import dev.harnessprotocol.SessionRequirements
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.StopReason
import dev.harnessprotocol.TaskEvent
import dev.harnessprotocol.TaskInput
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskOutput
import dev.harnessprotocol.TaskRequirements
import dev.harnessprotocol.TaskState
import dev.harnessprotocol.PersistenceRequirement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 정상 흐름·terminal 유일성·observer 독립성·session 직렬화·취소 경쟁을 검사한다.
 *
 * 각 `@Test`는 [docs/testing.md](../../../../../../docs/testing.md)의 "필수 공통 시나리오" 항목
 * 하나 이상에 대응한다. adapter가 native SDK를 아는 방식과 무관하게, 이 클래스는 오직
 * [HarnessFixture]와 공개 Port만으로 검사를 구동한다 — RecordingBridge나 provider wire를 요구하지
 * 않는다.
 */
abstract class HarnessConformanceCoreTest : ConformanceTestSupport() {

    // ------------------------------------------------------------------------------------------- 수락과 handle

    @Test
    fun `startTask accepts and returns a handle spanning several internal calls`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start("do several things")
            val control = task.control()
            control.reportRunning()
            control.reportToolCall("step-1", "search")
            control.reportToolResult("step-1", "ok")
            control.reportToolCall("step-2", "write")
            control.reportToolResult("step-2", "ok")
            control.reportCompletion()
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- 네 outcome과 state 일치

    @Test
    fun `completed outcome matches terminal state and settles exactly once`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion(OutputObservation.Text("done"), StopReason.FINISHED)
            control.reportFailure("late, must be ignored") // late duplicate terminal report
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
            assertEquals(TaskState.COMPLETED, task.state.value)
            assertEquals("done", (outcome.output as TaskOutput.Text).text)
        } finally {
            h.close()
        }
    }

    @Test
    fun `failed outcome matches terminal state and carries its kind`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            control.reportFailure("boom", FailureKind.PROVIDER)
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Failed)
            assertEquals(FailureKind.PROVIDER, (outcome as TaskOutcome.Failed).kind)
            assertEquals(TaskState.FAILED, task.state.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun `cancelled outcome matches terminal state`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val task = session.start()
            val control = task.control()
            control.reportRunning()
            task.requestCancellation()
            control.reportCancelledTermination()
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Cancelled)
            assertEquals(TaskState.CANCELLED, task.state.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun `natural language failure without a classified kind reports UNKNOWN, not a fabricated kind`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportFailure("something went wrong, no structured detail")
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Failed
            assertEquals(FailureKind.UNKNOWN, outcome.kind)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------- terminal 전 pending 정리와 늦은 응답 거절

    @Test
    fun `pending clears and terminal state is confirmed before awaitOutcome returns, on completion`(): Unit = runBlocking {
        val h = harnessFor("approval")
        try {
            val session = h.session(profile("approval").cases.first().sessionSpec)
            val task = session.start("do the guarded thing")
            val control = task.control()
            control.reportRunning()
            launch(Dispatchers.Default) {
                waitUntil { task.pendingInteractions.value.isNotEmpty() }
                // completion arrives while an approval is still open; the request must be
                // cleared (TASK_ENDED) before awaitOutcome can return.
                control.reportCompletion()
            }
            launch(Dispatchers.Default) {
                control.attemptGuardedEffect("w", "target", "do it", dev.harnessprotocol.EffectKind.COMMAND)
            }
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
            assertTrue(task.pendingInteractions.value.isEmpty())
            assertEquals(TaskState.COMPLETED, task.state.value)
            val pendingId = dev.harnessprotocol.InteractionId("does-not-exist")
            assertFailsWith<IllegalStateException> { task.respond(pendingId, dev.harnessprotocol.InteractionResponse.Approval(dev.harnessprotocol.ApprovalDecision.APPROVE_ONCE)) }
        } finally {
            h.close()
        }
    }

    @Test
    fun `pending clears and terminal state is confirmed before awaitOutcome returns, on failure`(): Unit = runBlocking {
        val h = harnessFor("questions")
        try {
            val session = h.session(profile("questions").cases.first().sessionSpec)
            val task = session.start("ask me something first")
            val control = task.control()
            control.reportRunning()
            launch(Dispatchers.Default) { control.askQuestion("what next?") }
            waitUntil { task.pendingInteractions.value.isNotEmpty() }
            control.reportFailure("gave up", FailureKind.PROVIDER)
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Failed)
            assertTrue(task.pendingInteractions.value.isEmpty())
        } finally {
            h.close()
        }
    }

    // ---------------------------------------------------------------------------------- observer 독립성

    @Test
    fun `completes without any event collector`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            repeat(300) { control.reportMessageDelta("m", "chunk$it ") }
            control.reportCompletion()
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a slow collector observes a gap but still sees the terminal event last`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<TaskEvent>()
            val collector = launch(Dispatchers.Default) {
                task.events.collect { event ->
                    seen += event
                    if (seen.size == 1) gate.await()
                }
            }
            // the collector must actually be subscribed (and stalled on its first event) before
            // flooding starts. `launch` only schedules the coroutine — it gives no guarantee the
            // multicast subscription is registered yet, so a single reportRunning() can race ahead
            // of it and be silently dropped with nobody there to receive it (the collector then
            // waits forever for an event that already vanished). Nudge repeatedly until one lands.
            withTimeout(5_000) {
                while (seen.isEmpty()) {
                    control.reportRunning()
                    delay(5)
                }
            }
            repeat(2_000) { control.reportMessageDelta("m", "chunk$it ") }
            control.reportCompletion()
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed)
            gate.complete(Unit)
            waitUntil { seen.lastOrNull()?.let { it is TaskEvent.Terminal } == true }
            collector.cancel()
            assertTrue(seen.any { it is TaskEvent.ObservationGap }, "a stalled collector must observe a gap")
            assertTrue(seen.last() is TaskEvent.Terminal, "terminal must survive and arrive last: $seen")
            assertEquals(1, seen.count { it is TaskEvent.Terminal })
        } finally {
            h.close()
        }
    }

    @Test
    fun `a late subscriber still receives the terminal event`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion()
            withTimeout(5_000) { task.awaitOutcome() }
            val late = withTimeout(2_000) { task.events.first() }
            assertTrue(late is TaskEvent.Terminal)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------- session 직렬화와 문맥 분리

    @Test
    fun `overlapping start on one session is rejected before it reaches the native boundary`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val start = fixture().controlNextStart(session)
            session.start("first")
            val before = start.observedSubmissions()
            assertFailsWith<IllegalStateException> { session.start("second") }
            assertEquals(before, start.observedSubmissions(), "a rejected overlapping start must not reach the native boundary")
        } finally {
            h.close()
        }
    }

    @Test
    fun `a session accepts its next task only once the previous one is terminal`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            val first = session.start("first")
            first.control().reportRunning()
            first.control().reportCompletion()
            withTimeout(5_000) { first.awaitOutcome() }
            val second = session.start("second") // terminal → allowed
            assertNotEquals(first.id, second.id)
        } finally {
            h.close()
        }
    }

    @Test
    fun `different sessions on one harness make progress concurrently without mixing context`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val a = h.session()
            val b = h.session()
            val taskA = a.start("a")
            val taskB = b.start("b")
            val controlA = taskA.control()
            val controlB = taskB.control()
            controlA.reportRunning()
            controlB.reportRunning()
            controlA.reportCompletion(OutputObservation.Text("A"))
            controlB.reportCompletion(OutputObservation.Text("B"))
            val outcomeA = withTimeout(5_000) { taskA.awaitOutcome() } as TaskOutcome.Completed
            val outcomeB = withTimeout(5_000) { taskB.awaitOutcome() } as TaskOutcome.Completed
            assertEquals("A", (outcomeA.output as TaskOutput.Text).text)
            assertEquals("B", (outcomeB.output as TaskOutput.Text).text)
            assertNotEquals(taskA.sessionId, taskB.sessionId)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------------- 취소 경쟁

    @Test
    fun `natural completion wins the race against a cancellation request`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            task.requestCancellation()
            control.reportCompletion(OutputObservation.Text("done anyway"))
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Completed, "completion must be allowed to win: $outcome")
            assertEquals(TaskState.COMPLETED, task.state.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun `cancellation requested after terminal is a no-op`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion()
            withTimeout(5_000) { task.awaitOutcome() }
            task.requestCancellation() // must not throw and must not change the outcome
            assertEquals(TaskState.COMPLETED, task.state.value)
        } finally {
            h.close()
        }
    }

    // --------------------------------------------------------------------------- 산출물 null vs 빈 문자열

    @Test
    fun `a normal completion with no captured output is Completed with a null output`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion(output = null, stopReason = StopReason.FINISHED)
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Completed
            assertNull(outcome.output, "no output was ever captured, so the outcome must not fabricate one")
        } finally {
            h.close()
        }
    }

    @Test
    fun `an actual empty string output is distinct from no output`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportCompletion(OutputObservation.Text(""), StopReason.FINISHED)
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Completed
            val output = outcome.output as TaskOutput.Text
            assertEquals("", output.text)
        } finally {
            h.close()
        }
    }

    @Test
    fun `completion's null output does not erase output already captured earlier`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportOutput(OutputObservation.Text("partial so far", complete = false))
            control.reportCompletion(output = null, stopReason = StopReason.FINISHED)
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Completed
            assertEquals("partial so far", (outcome.output as TaskOutput.Text).text)
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------------------- 완료는 업무 성공/schema 검증이 아님

    @Test
    fun `completion does not imply schema-valid structured output`(): Unit = runBlocking {
        val h = harnessFor("structured-output")
        try {
            val spec = SessionSpec()
            val session = h.session(spec)
            val requirements = TaskRequirements(
                output = dev.harnessprotocol.OutputRequirement.Structured(schema = "{\"type\":\"object\"}", validatedByHarness = true),
            )
            val task = session.startTask(textRequest("produce structured output", requirements))
            val control = task.control()
            control.reportRunning()
            control.reportOutput(OutputObservation.Structured("not-json-at-all", complete = true, reportedValidation = SchemaValidation.INVALID))
            control.reportCompletion()
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Completed
            val output = outcome.output as TaskOutput.Structured
            assertEquals(SchemaValidation.INVALID, output.validation, "an invalid structured payload must still be reported as Completed")
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- 잘못된 요구 vs handle 이후 실패

    @Test
    fun `an incompatible session requirement is rejected before any handle is issued`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val spec = SessionSpec(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
            assertFailsWith<IncompatibleRequirementException> { h.createSession(spec) }
        } finally {
            h.close()
        }
    }

    @Test
    fun `an unconfirmed session requirement is distinguished from a confirmed rejection`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val spec = SessionSpec(
                requirements = SessionRequirements(
                    execution = dev.harnessprotocol.ExecutionConstraint.Required(network = dev.harnessprotocol.NetworkAccess.ALLOWED),
                ),
            )
            assertFailsWith<RequirementUnconfirmedException> { h.createSession(spec) }
        } finally {
            h.close()
        }
    }

    @Test
    fun `a request-level rejection is distinguished from a post-handle failure`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val session = h.session()
            // A bad request never reaches a handle at all:
            assertFailsWith<IllegalArgumentException> { TaskInput.Text("") }
            // Whereas a handle obtained cleanly can still fail after the fact:
            val task = session.start()
            task.control().reportRunning()
            task.control().reportFailure("provider rejected it", FailureKind.POLICY_BLOCKED)
            val outcome = withTimeout(5_000) { task.awaitOutcome() }
            assertTrue(outcome is TaskOutcome.Failed)
        } finally {
            h.close()
        }
    }

    // --------------------------------------------------------------------------------------- identity 격리

    @Test
    fun `the same work key in two different tasks does not cross-contaminate effect counts`(): Unit = runBlocking {
        val h = harnessFor("approval")
        try {
            val spec = profile("approval").cases.first().sessionSpec
            val session = h.session(spec)

            val first = session.start("first")
            val firstControl = first.control()
            firstControl.reportRunning()
            approveAll(first, firstControl)
            firstControl.attemptGuardedEffect("shared-key", "target", "do it", dev.harnessprotocol.EffectKind.COMMAND)
            assertEquals(1, firstControl.observedEffects("shared-key"))
            firstControl.reportCompletion()
            withTimeout(5_000) { first.awaitOutcome() }

            val second = session.start("second")
            val secondControl = second.control()
            secondControl.reportRunning()
            assertEquals(0, secondControl.observedEffects("shared-key"), "a fresh task must not inherit a previous task's effect count")
            secondControl.reportCompletion()
            withTimeout(5_000) { second.awaitOutcome() }
        } finally {
            h.close()
        }
    }

    /** [pendingInteractions]에 approval 요청이 뜨는 즉시 APPROVE_ONCE로 응답하는 백그라운드 responder. */
    private fun kotlinx.coroutines.CoroutineScope.approveAll(
        task: dev.harnessprotocol.AgentTask,
        @Suppress("UNUSED_PARAMETER") control: TaskControl,
    ) = launch(Dispatchers.Default) {
        val request = run {
            waitUntil { task.pendingInteractions.value.isNotEmpty() }
            task.pendingInteractions.value.first()
        }
        task.respond(request.interactionId, dev.harnessprotocol.InteractionResponse.Approval(dev.harnessprotocol.ApprovalDecision.APPROVE_ONCE))
    }

    @Test
    fun `whitespace-only input text is preserved verbatim, not trimmed`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start("   ")
            assertEquals("   ", (task.control().observedInput() as TaskInput.Text).text)
            task.control().reportRunning()
            task.control().reportCompletion()
            withTimeout(5_000) { task.awaitOutcome() }
        } finally {
            h.close()
        }
    }

    @Test
    fun `null instructions and explicit empty instructions are distinguished`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val defaultSession = h.session(SessionSpec(instructions = null))
            val explicitSession = h.session(SessionSpec(instructions = ""))
            val t1 = defaultSession.start()
            val t2 = explicitSession.start()
            assertNull(t1.control().observedInstructions())
            assertEquals("", t2.control().observedInstructions())
            listOf(t1, t2).forEach {
                it.control().reportRunning()
                it.control().reportCompletion()
            }
            listOf(t1, t2).forEach { withTimeout(5_000) { it.awaitOutcome() } }
        } finally {
            h.close()
        }
    }

    @Test
    fun `only activated skills are observed, even when more are provided`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val spec = SessionSpec(
                requirements = SessionRequirements(
                    workspace = dev.harnessprotocol.WorkspaceRequirement.Required(
                        skills = listOf(
                            dev.harnessprotocol.SkillReference(name = "on", path = "/skills/on", activate = true),
                            dev.harnessprotocol.SkillReference(name = "off", path = "/skills/off", activate = false),
                        ),
                    ),
                ),
            )
            val task = h.session(spec).start()
            assertEquals(setOf("on"), task.control().observedActivatedSkills())
            task.control().reportRunning()
            task.control().reportCompletion()
            withTimeout(5_000) { task.awaitOutcome() }
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------- 부분 산출물은 실패·취소·미확정에서도 회수된다

    @Test
    fun `output captured before a failure is still recovered in the Failed outcome`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportOutput(OutputObservation.Text("partial before the crash", complete = false))
            control.reportFailure("provider crashed", FailureKind.PROVIDER)
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Failed
            val output = outcome.output as TaskOutput.Text
            assertEquals("partial before the crash", output.text)
            assertFalse(output.complete, "a partial output must not be reported as complete")
        } finally {
            h.close()
        }
    }

    @Test
    fun `output captured before a cancellation is still recovered in the Cancelled outcome`(): Unit = runBlocking {
        val h = harnessFor()
        try {
            val task = h.session().start()
            val control = task.control()
            control.reportRunning()
            control.reportOutput(OutputObservation.Text("partial before cancelling", complete = false))
            task.requestCancellation()
            control.reportCancelledTermination()
            val outcome = withTimeout(5_000) { task.awaitOutcome() } as TaskOutcome.Cancelled
            assertEquals("partial before cancelling", (outcome.output as TaskOutput.Text).text)
        } finally {
            h.close()
        }
    }

    @Test
    fun `output captured before an Unresolved settlement is still recovered, not reported as final`(): Unit = runBlocking {
        val h = harnessFor()
        val session = h.session()
        val task = session.start()
        val control = task.control()
        control.reportRunning()
        control.reportOutput(OutputObservation.Text("last known snapshot", complete = false))
        session.release() // nobody confirms cancellation → settles Unresolved after the cleanup budget
        val outcome = withTimeout(2_000) { task.awaitOutcome() } as TaskOutcome.Unresolved
        val output = outcome.output as TaskOutput.Text
        assertEquals("last known snapshot", output.text)
        assertFalse(output.complete, "Unresolved must not claim this partial snapshot is the actual final result")
        h.close()
    }
}
