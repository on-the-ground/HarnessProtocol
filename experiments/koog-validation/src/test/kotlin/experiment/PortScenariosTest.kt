package experiment

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Consumers assert the existing public Port and fixture business effects, not bridge/graph internals. */
@org.junit.jupiter.api.Timeout(20)
class PortScenariosTest {
    @TempDir lateinit var directory: Path
    private fun harness(executor: ScriptedExecutor, ops: FixtureOperations = FixtureOperations(), grace: Long = 2000, iterations: Int = 20) =
        KoogHarness(ConversationStore(directory), { executor }, ops, grace, iterations)
    private val approvalSpec = AgentSpec(executionPolicy = ExecutionPolicy(approval = ApprovalPolicy.CALLER_DECIDES))
    private suspend fun AgentExecution.pending(): InteractionRequest.Approval = withTimeout(5000) {
        pendingInteractions.first { it.isNotEmpty() }.single() as InteractionRequest.Approval
    }
    private suspend fun AgentExecution.result() = withTimeout(5000) { awaitResult() }

    @Test fun `slow observation does not block actual graph and reports dropped events`() = runBlocking<Unit> {
        val begin = CompletableDeferred<Unit>()
        val steps = (0 until 10).map { index ->
            val step: suspend (ai.koog.prompt.Prompt) -> ai.koog.prompt.message.Message.Assistant = {
                if (index == 0) begin.await()
                call("change_status", """{"requestId":"R-1"}""", "change-$index")
            }
            step
        } + listOf<suspend (ai.koog.prompt.Prompt) -> ai.koog.prompt.message.Message.Assistant>({ answer("All changes declined") })
        val ops = FixtureOperations()
        harness(ScriptedExecutor(*steps.toTypedArray()), ops, iterations = 100).use { h ->
            val execution = h.createSession(AgentSpec()).execute(AgentInput.Text("Review"))
            val unblockObserver = CompletableDeferred<Unit>()
            val seen = mutableListOf<AgentEvent>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                execution.events.collect { seen += it; unblockObserver.await() }
            }
            try {
                begin.complete(Unit)
                execution.result()
                assertEquals(ExecutionState.COMPLETED, execution.state.value)
                assertEquals(0, ops.changes.get())
            } finally { unblockObserver.complete(Unit) }
            withTimeout(5000) { observer.join() }
            assertTrue(seen.any { it is AgentEvent.ObservationGap })
            assertIs<AgentEvent.ExecutionCompleted>(seen.last())
        }
    }

    @Test fun `immediate cancellation settles without waiting for the model`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        harness(ScriptedExecutor({ entered.await(); answer("Unreachable") })).use { h ->
            val execution = h.createSession(AgentSpec()).execute(AgentInput.Text("Review"))
            execution.cancel()
            assertFailsWith<AgentExecutionCancelledException> { execution.result() }
            assertEquals(ExecutionState.CANCELLED, execution.state.value)
        }
    }

    @Test fun `S1 tool execution result and unknown usage work without an observer`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val executor = ScriptedExecutor({ lookupCall() }, { answer("Review completed with policy-A") })
        harness(executor, ops).use { h ->
            val execution = h.createSession(AgentSpec()).execute(AgentInput.Text("Review R-1"))
            assertEquals("Review completed with policy-A", execution.result().finalMessage)
            assertNull(execution.result().usage)
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
            assertEquals(1, ops.reads.get())
            assertIs<AgentEvent.ExecutionCompleted>(execution.events.first())
        }
        println("S1 PORT: actual tool call=1, canonical result, late terminal, usage=unknown")
    }

    @Test fun `S2 approval gates actual effect and rejects unsupported or duplicate decisions`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        harness(ScriptedExecutor({ changeCall() }, { answer("Changed") }), ops).use { h ->
            val execution = h.createSession(approvalSpec).execute(AgentInput.Text("Process R-1"))
            val request = execution.pending()
            assertEquals(ExecutionState.WAITING, execution.state.value)
            assertEquals(0, ops.changes.get())
            assertFailsWith<IllegalArgumentException> { execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_FOR_SESSION)) }
            assertEquals(0, ops.changes.get())
            val observed = mutableListOf<AgentEvent>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { execution.events.collect { observed += it } }
            execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
            execution.result()
            observer.join()
            assertEquals(1, ops.changes.get())
            assertTrue(execution.pendingInteractions.value.isEmpty())
            val tool = observed.filterIsInstance<AgentEvent.ToolCallChanged>().single { it.status == WorkStatus.COMPLETED }
            val effect = observed.filterIsInstance<AgentEvent.EffectChanged>().single { it.status == WorkStatus.COMPLETED }
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
            val execution = h.createSession(approvalSpec).execute(AgentInput.Text("Process R-1"))
            val request = execution.pending()
            execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.DECLINE))
            assertEquals(StopReason.FINISHED, execution.result().stopReason)
            assertEquals(0, ops.changes.get())
            assertEquals(2, executor.prompts.size)
        }
    }

    @Test fun `S2 native question cannot be represented by approval response`() = runBlocking<Unit> {
        var received = ""
        val nativeOps = object : FixtureOperations() {
            override suspend fun askQuestion(question: String): String { received = question; return "scope=internal" }
        }
        val questionCall = { call("ask_question", """{"question":"Which scope?"}""") }
        val nativeExecutor = ScriptedExecutor({ questionCall() }, { answer("Internal scope selected") })
        val nativeAgent = reviewAgent(nativeExecutor, nativeOps)
        try { nativeAgent.run("Review ambiguous request") } finally { nativeAgent.close() }
        assertEquals("Which scope?", received)

        val portExecutor = ScriptedExecutor({ questionCall() }, { prompt ->
            assertTrue(prompt.messages.joinToString { it.toString() }.contains("no question response contract"))
            answer("Cannot obtain missing scope through this adapter")
        })
        harness(portExecutor).use { h ->
            val execution = h.createSession(approvalSpec).execute(AgentInput.Text("Review ambiguous request"))
            assertTrue(execution.result().finalMessage.contains("Cannot obtain"))
            assertTrue(execution.pendingInteractions.value.isEmpty())
        }
        println("S2 QUESTION GAP: native caller text answer works; existing sealed approval response cannot carry it")
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
        val session: AgentSession = h.createSession(AgentSpec(instructions = "First policy"))
        session.execute(AgentInput.Text("Remember marker-alpha")).result()
        session.execute(AgentInput.Text("Add a constraint")).result()
        session.release()
        assertFailsWith<IllegalStateException> { session.execute(AgentInput.Text("Invalid")) }
        val resumed = h.resumeSession(session.id, AgentSpec(instructions = "Updated policy"))
        resumed.execute(AgentInput.Text("Continue after release")).result()
        h.close()
        val restartedExecutor = ScriptedExecutor({ prompt ->
            val messages = prompt.messages.joinToString { it.textContent() }
            assertTrue("marker-alpha" in messages && "marker-beta" in messages && "marker-gamma" in messages)
            assertTrue("Final policy" in messages)
            assertFalse("First policy" in messages)
            answer("Restored")
        })
        harness(restartedExecutor).use { restarted ->
            val restored = restarted.resumeSession(session.id, AgentSpec(instructions = "Final policy"))
            assertEquals("Restored", restored.execute(AgentInput.Text("Continue after runtime recreation")).result().finalMessage)
            assertFailsWith<HarnessTransportException> { restarted.resumeSession(SessionId("unknown"), AgentSpec()) }
        }
        println("S3 PORT: next input + release/resume + fresh harness restored completed conversation, instructions override preserved")
    }

    @Test fun `S4 active session rejects overlap and cooperative cancel settles once`() = runBlocking<Unit> {
        val ops = FixtureOperations().apply { lookupGate = CompletableDeferred() }
        harness(ScriptedExecutor({ lookupCall() }, { answer("Unexpected") }), ops).use { h ->
            val session = h.createSession(AgentSpec())
            val execution = session.execute(AgentInput.Text("Review"))
            withTimeout(5000) { ops.lookupEntered.await() }
            assertFailsWith<IllegalStateException> { session.execute(AgentInput.Text("Overlap")) }
            execution.cancel()
            assertFailsWith<AgentExecutionCancelledException> { execution.result() }
            assertEquals(ExecutionState.CANCELLED, execution.state.value)
            execution.cancel()
            assertEquals(0, ops.changes.get())
        }
    }

    @Test fun `S4 pending cancellation clears interaction and prevents effect`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        harness(ScriptedExecutor({ changeCall() }), ops).use { h ->
            val execution = h.createSession(approvalSpec).execute(AgentInput.Text("Process"))
            val request = execution.pending()
            execution.cancel()
            assertFailsWith<AgentExecutionCancelledException> { execution.result() }
            assertTrue(execution.pendingInteractions.value.isEmpty())
            assertEquals(0, ops.changes.get())
            assertFailsWith<IllegalStateException> { execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE)) }
        }
    }

    @Test fun `S4 close cancels pending execution and completion wins after completion`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val h = harness(ScriptedExecutor({ changeCall() }), ops)
        val waiting = h.createSession(approvalSpec).execute(AgentInput.Text("Process"))
        waiting.pending()
        h.close()
        assertFailsWith<AgentExecutionCancelledException> { waiting.result() }
        assertEquals(0, ops.changes.get())
        harness(ScriptedExecutor({ answer("Done") })).use { completedHarness ->
            val execution = completedHarness.createSession(AgentSpec()).execute(AgentInput.Text("Review"))
            execution.result()
            execution.cancel()
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
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
        val execution = h.createSession(approvalSpec).execute(AgentInput.Text("Process"))
        val request = execution.pending()
        execution.respond(request.interactionId, InteractionResponse.Approval(ApprovalDecision.APPROVE_ONCE))
        withTimeout(5000) { entered.await() }
        try {
            assertFailsWith<HarnessTransportException> { h.close() }
            assertEquals(0, ops.changes.get())
            assertEquals(ExecutionState.RUNNING, execution.state.value)
        } finally { releaseEffect.complete(Unit) }
        assertFailsWith<AgentExecutionCancelledException> { execution.result() }
        assertEquals(1, ops.changes.get())
        println("S4 COUNTEREXAMPLE: close grace expired before effect; effect occurred later; cancellation confirmed only after tool returned")
    }

    @Test fun `failure and iteration limit do not become invented successful responses`() = runBlocking<Unit> {
        harness(ScriptedExecutor({ error("controlled provider failure") })).use { h ->
            val execution = h.createSession(AgentSpec()).execute(AgentInput.Text("Review"))
            val error = assertFailsWith<AgentExecutionFailedException> { execution.result() }
            assertEquals(FailureKind.PROVIDER, error.kind)
            assertEquals(ExecutionState.FAILED, execution.state.value)
        }
        harness(ScriptedExecutor({ lookupCall() }), iterations = 1).use { h ->
            val execution = h.createSession(AgentSpec()).execute(AgentInput.Text("Review"))
            val result = execution.result()
            assertEquals(StopReason.TURN_LIMIT, result.stopReason)
            assertEquals("", result.finalMessage)
            assertEquals(ExecutionState.COMPLETED, execution.state.value)
        }
        println("STOP-REASON PRESERVED: native iteration exception maps to COMPLETED + TURN_LIMIT, no invented final response")
    }

    @Test fun `unsupported configuration is rejected before model or business tool execution`() = runBlocking<Unit> {
        val executor = ScriptedExecutor()
        harness(executor).use { h ->
            val unsupported = AgentSpec(executionPolicy = ExecutionPolicy(filesystem = FilesystemAccess.ReadOnly, network = NetworkAccess.DENIED))
            assertFalse(h.validate(unsupported).isCompatible)
            assertFailsWith<IncompatibleAgentSpecException> { h.createSession(unsupported) }
            assertTrue(executor.prompts.isEmpty())
        }
    }

    @Test fun `S1 text result can carry structured data but does not guarantee its schema`() = runBlocking<Unit> {
        harness(ScriptedExecutor({ answer("""{"requestId":"R-1","action":"review","reason":"policy-A"}""") }, { answer("not JSON") })).use { h ->
            val session = h.createSession(AgentSpec())
            val first = session.execute(AgentInput.Text("Provide a processing proposal")).result()
            assertEquals("R-1", Json.decodeFromString<ReviewOutcome>(first.finalMessage).requestId)
            val second = session.execute(AgentInput.Text("Provide another proposal")).result()
            assertEquals(StopReason.FINISHED, second.stopReason)
            assertFails { Json.decodeFromString<ReviewOutcome>(second.finalMessage) }
        }
        println("RESULT: string payload transport is possible; schema validity remains an application check in 0.1.0")
    }
}
