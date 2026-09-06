package experiment

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Consumers assert the current public Port and fixture business effects, not bridge/graph internals. */
@org.junit.jupiter.api.Timeout(20)
class PortScenariosTest {
    @TempDir lateinit var directory: Path
    private fun harness(executor: ScriptedExecutor, ops: FixtureOperations = FixtureOperations(), grace: Long = 2000, iterations: Int = 20) =
        KoogHarness(ConversationStore(directory), { executor }, ops, grace, iterations)
    private val approvalSpec = SessionSpec(requirements = SessionRequirements(approval = ApprovalRequirement.CallerDecides))
    private suspend fun AgentTask.pending(): InteractionRequest.Approval = withTimeout(5000) {
        pendingInteractions.first { it.isNotEmpty() }.single() as InteractionRequest.Approval
    }
    private suspend fun AgentTask.outcome() = withTimeout(5000) { awaitOutcome() }

    @Test fun `slow observation does not block actual graph and reports dropped events`() = runBlocking<Unit> {
        val begin = CompletableDeferred<Unit>()
        val steps = (0 until 80).map { index ->
            val step: suspend (ai.koog.prompt.Prompt) -> ai.koog.prompt.message.Message.Assistant = {
                if (index == 0) begin.await()
                call("change_status", """{"requestId":"R-1"}""", "change-$index")
            }
            step
        } + listOf<suspend (ai.koog.prompt.Prompt) -> ai.koog.prompt.message.Message.Assistant>({ answer("All changes declined") })
        val ops = FixtureOperations()
        harness(ScriptedExecutor(*steps.toTypedArray()), ops, iterations = 1000).use { h ->
            val execution = h.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review")))
            val unblockObserver = CompletableDeferred<Unit>()
            val seen = mutableListOf<TaskEvent>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { seen += it; unblockObserver.await() }
            }
            try {
                begin.complete(Unit)
                execution.outcome()
                assertEquals(TaskState.COMPLETED, execution.state.value)
                assertEquals(0, ops.changes.get())
            } finally { unblockObserver.complete(Unit) }
            withTimeout(5000) { observer.join() }
            assertTrue(seen.any { it is TaskEvent.ObservationGap })
            assertIs<TaskEvent.TaskCompleted>(seen.last())
        }
    }

    @Test fun `immediate cancellation settles without waiting for the model`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        harness(ScriptedExecutor({ entered.await(); answer("Unreachable") })).use { h ->
            val execution = h.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review")))
            execution.requestCancellation()
            assertIs<TaskOutcome.Cancelled>(execution.outcome())
            assertEquals(TaskState.CANCELLED, execution.state.value)
        }
    }

    @Test fun `S1 tool execution result and unknown usage work without an observer`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val executor = ScriptedExecutor({ lookupCall() }, { answer("Review completed with policy-A") })
        harness(executor, ops).use { h ->
            val execution = h.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review R-1")))
            assertEquals("Review completed with policy-A", assertIs<TaskOutput.Text>(execution.outcome().output).text)
            assertEquals(AgentUsage.Unknown, execution.outcome().usage)
            assertEquals(TaskState.COMPLETED, execution.state.value)
            assertEquals(1, ops.reads.get())
            assertIs<TaskEvent.TaskCompleted>(execution.events.first())
        }
        println("S1 PORT: actual tool call=1, canonical result, late terminal, usage=unknown")
    }

    @Test fun `S2 approval gates actual effect and rejects unsupported or duplicate decisions`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        harness(ScriptedExecutor({ changeCall() }, { answer("Changed") }), ops).use { h ->
            val execution = h.createSession(approvalSpec).startTask(TaskRequest(TaskInput.Text("Process R-1")))
            val request = execution.pending()
            assertEquals(TaskState.AWAITING_RESPONSE, execution.state.value)
            assertEquals(0, ops.changes.get())
            assertFailsWith<IllegalArgumentException> { execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_FOR_SESSION)) }
            assertEquals(0, ops.changes.get())
            val observed = mutableListOf<TaskEvent>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { execution.events.collect { observed += it } }
            execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
            execution.outcome()
            observer.join()
            assertEquals(1, ops.changes.get())
            assertTrue(execution.pendingInteractions.value.isEmpty())
            val tool = observed.filterIsInstance<TaskEvent.ToolCallChanged>().single { it.status == WorkStatus.COMPLETED }
            val effect = observed.filterIsInstance<TaskEvent.EffectChanged>().single { it.status == WorkStatus.COMPLETED }
            assertEquals(tool.workId, effect.workId)
            assertEquals(EffectKind.OTHER, effect.kind)
            assertFailsWith<IllegalStateException> { execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)) }
        }
        println("S2 PORT: before approval=0 effects, after approval=1; tool/effect share identity")
    }

    @Test fun `S2 decline continues agent with no business mutation`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val executor = ScriptedExecutor({ changeCall() }, { prompt ->
            assertTrue(prompt.messages.joinToString { it.toString() }.contains("declined"))
            answer("Change declined; review remains available")
        })
        harness(executor, ops).use { h ->
            val execution = h.createSession(approvalSpec).startTask(TaskRequest(TaskInput.Text("Process R-1")))
            val request = execution.pending()
            execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.DECLINE))
            assertEquals(StopReason.FINISHED, assertIs<TaskOutcome.Completed>(execution.outcome()).stopReason)
            assertEquals(0, ops.changes.get())
            assertEquals(2, executor.prompts.size)
        }
    }

    @Test fun `S2 typed question carries a caller answer without treating it as approval`() = runBlocking<Unit> {
        val executor = ScriptedExecutor({ call("ask_question", """{"question":"Which scope?"}""") }, { prompt ->
            assertTrue(prompt.messages.joinToString { it.toString() }.contains("scope=internal"))
            answer("Internal scope selected")
        })
        harness(executor).use { h ->
            val spec = SessionSpec(requirements = SessionRequirements(questions = QuestionRequirement.CallerAnswers))
            val task = h.createSession(spec).startTask(TaskRequest(TaskInput.Text("Review ambiguous request")))
            val request = withTimeout(5000) { task.pendingInteractions.first { it.isNotEmpty() }.single() }
            assertIs<InteractionRequest.Question>(request)
            assertFailsWith<IllegalArgumentException> { task.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)) }
            task.respond(request.interactionId, InteractionResponse.Answer("scope=internal"))
            assertEquals("Internal scope selected", assertIs<TaskOutput.Text>(task.outcome().output).text)
            assertTrue(task.pendingInteractions.value.isEmpty())
        }
    }

    @Test fun `S3 completed context survives next input release and new harness`() = runBlocking<Unit> {
        val executor = ScriptedExecutor({ answer("Decision: marker-alpha") }, { prompt ->
            assertTrue(prompt.messages.joinToString { it.textContent() }.contains("marker-alpha"))
            answer("Decision: marker-beta")
        }, { prompt ->
            assertTrue(prompt.messages.joinToString { it.textContent() }.contains("marker-beta"))
            answer("Decision: marker-gamma")
        })
        val h = harness(executor)
        val session: AgentSession = h.createSession(SessionSpec(instructions = "First policy", requirements = SessionRequirements(persistence = PersistenceRequirement.Required())))
        session.startTask(TaskRequest(TaskInput.Text("Remember marker-alpha"))).outcome()
        session.startTask(TaskRequest(TaskInput.Text("Add a constraint"))).outcome()
        session.release()
        assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("Invalid"))) }
        val resumed = h.reopenSession(requireNotNull(session.persistentRef), session.spec.copy(instructions = "Updated policy"))
        resumed.startTask(TaskRequest(TaskInput.Text("Continue after release"))).outcome()
        h.close()
        val restartedExecutor = ScriptedExecutor({ prompt ->
            val messages = prompt.messages.joinToString { it.textContent() }
            assertTrue("marker-alpha" in messages && "marker-beta" in messages && "marker-gamma" in messages)
            assertTrue("Final policy" in messages)
            assertFalse("First policy" in messages)
            answer("Restored")
        })
        harness(restartedExecutor).use { restarted ->
            val restored = restarted.reopenSession(requireNotNull(session.persistentRef), session.spec.copy(instructions = "Final policy"))
            assertEquals("Restored", assertIs<TaskOutput.Text>(restored.startTask(TaskRequest(TaskInput.Text("Continue after runtime recreation"))).outcome().output).text)
            assertFailsWith<HarnessTransportException> { restarted.reopenSession(requireNotNull(session.persistentRef).copy(id = "unknown"), session.spec) }
        }
        println("S3 PORT: next input + release/resume + fresh harness restored completed conversation, instructions override preserved")
    }

    @Test fun `S4 active session rejects overlap and cooperative cancel settles once`() = runBlocking<Unit> {
        val ops = FixtureOperations().apply { lookupGate = CompletableDeferred() }
        harness(ScriptedExecutor({ lookupCall() }, { answer("Unexpected") }), ops).use { h ->
            val session = h.createSession(SessionSpec())
            val execution = session.startTask(TaskRequest(TaskInput.Text("Review")))
            withTimeout(5000) { ops.lookupEntered.await() }
            assertFailsWith<IllegalStateException> { session.startTask(TaskRequest(TaskInput.Text("Overlap"))) }
            execution.requestCancellation()
            assertIs<TaskOutcome.Cancelled>(execution.outcome())
            assertEquals(TaskState.CANCELLED, execution.state.value)
            execution.requestCancellation()
            assertEquals(0, ops.changes.get())
        }
    }

    @Test fun `S4 pending cancellation clears interaction and prevents effect`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        harness(ScriptedExecutor({ changeCall() }), ops).use { h ->
            val execution = h.createSession(approvalSpec).startTask(TaskRequest(TaskInput.Text("Process")))
            val request = execution.pending()
            execution.requestCancellation()
            assertIs<TaskOutcome.Cancelled>(execution.outcome())
            assertTrue(execution.pendingInteractions.value.isEmpty())
            assertEquals(0, ops.changes.get())
            assertFailsWith<IllegalStateException> { execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)) }
        }
    }

    @Test fun `S4 close cancels pending execution and completion wins after completion`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val h = harness(ScriptedExecutor({ changeCall() }), ops)
        val waiting = h.createSession(approvalSpec).startTask(TaskRequest(TaskInput.Text("Process")))
        waiting.pending()
        h.close()
        assertIs<TaskOutcome.Cancelled>(waiting.outcome())
        assertEquals(0, ops.changes.get())
        harness(ScriptedExecutor({ answer("Done") })).use { completedHarness ->
            val execution = completedHarness.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review")))
            execution.outcome()
            execution.requestCancellation()
            assertEquals(TaskState.COMPLETED, execution.state.value)
        }
    }

    @Test fun `S4 close timeout cannot prove noncooperative effect has stopped`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val ops = object : FixtureOperations() {
            override suspend fun changeStatus(requestId: String): String = withContext(NonCancellable) {
                entered.complete(Unit)
                releaseEffect.await()
                super.changeStatus(requestId)
            }
        }
        val h = harness(ScriptedExecutor({ changeCall() }, { answer("Done") }), ops, grace = 30)
        val execution = h.createSession(approvalSpec).startTask(TaskRequest(TaskInput.Text("Process")))
        val request = execution.pending()
        execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
        withTimeout(5000) { entered.await() }
        try {
            h.close()
            assertEquals(0, ops.changes.get())
            assertEquals(TaskState.UNRESOLVED, execution.state.value)
        } finally { releaseEffect.complete(Unit) }
        assertIs<TaskOutcome.Unresolved>(execution.outcome())
        withTimeout(5000) { while (ops.changes.get() == 0) delay(5) }
        assertEquals(1, ops.changes.get())
        println("S4 COUNTEREXAMPLE: close grace expired before effect; effect occurred later; public outcome remains unresolved after the late effect")
    }

    @Test fun `failure and iteration limit do not become invented successful responses`() = runBlocking<Unit> {
        harness(ScriptedExecutor({ error("controlled provider failure") })).use { h ->
            val execution = h.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review")))
            val error = assertIs<TaskOutcome.Failed>(execution.outcome())
            assertEquals(FailureKind.PROVIDER, error.kind)
            assertEquals(TaskState.FAILED, execution.state.value)
        }
        harness(ScriptedExecutor({ lookupCall() }), iterations = 1).use { h ->
            val execution = h.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("Review")))
            val result = assertIs<TaskOutcome.Completed>(execution.outcome())
            assertEquals(StopReason.ITERATION_LIMIT, result.stopReason)
            assertNull(result.output)
            assertEquals(TaskState.COMPLETED, execution.state.value)
        }
        println("STOP-REASON PRESERVED: native iteration exception maps to COMPLETED + ITERATION_LIMIT, no invented final response")
    }

    @Test fun `unsupported configuration is rejected before model or business tool execution`() = runBlocking<Unit> {
        val executor = ScriptedExecutor()
        harness(executor).use { h ->
            val unsupported = SessionSpec(requirements = SessionRequirements(execution = ExecutionConstraint.Required(FilesystemAccess.ReadOnly, NetworkAccess.DENIED)))
            assertFalse(h.validate(unsupported).isCompatible)
            assertFailsWith<IncompatibleRequirementException> { h.createSession(unsupported) }
            assertTrue(executor.prompts.isEmpty())
        }
    }

    @Test fun `S1 text result can carry structured data but does not guarantee its schema`() = runBlocking<Unit> {
        harness(ScriptedExecutor({ answer("""{"requestId":"R-1","action":"review","reason":"policy-A"}""") }, { answer("not JSON") })).use { h ->
            val session = h.createSession(SessionSpec())
            val first = session.startTask(TaskRequest(TaskInput.Text("Provide a processing proposal"))).outcome()
            assertEquals("R-1", Json.decodeFromString<ReviewOutcome>(assertIs<TaskOutput.Text>(first.output).text).requestId)
            val second = session.startTask(TaskRequest(TaskInput.Text("Provide another proposal"))).outcome()
            assertEquals(StopReason.FINISHED, assertIs<TaskOutcome.Completed>(second).stopReason)
            assertFails { Json.decodeFromString<ReviewOutcome>(assertIs<TaskOutput.Text>(second.output).text) }
        }
        println("RESULT: string payload transport is possible; text output does not claim schema validation")
    }
}
