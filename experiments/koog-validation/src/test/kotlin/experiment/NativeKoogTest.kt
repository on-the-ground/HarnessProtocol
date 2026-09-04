package experiment

import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

@org.junit.jupiter.api.Timeout(20)
class NativeKoogTest {
    @TempDir lateinit var directory: Path

    @Test fun `native file checkpoint resumes interrupted graph rather than accepting a new conversation turn`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val interrupted = reviewAgent(ScriptedExecutor({ lookupCall() }, { throw CancellationException("fixture interruption") }), ops) {
            install(Persistence) { storage = JVMFilePersistenceStorageProvider(directory) }
        }
        try {
            assertFailsWith<CancellationException> { interrupted.createSession("checkpoint-case").run("original-request-marker") }
        } finally { interrupted.close() }
        assertEquals(1, ops.reads.get())
        val reopenedStorage = JVMFilePersistenceStorageProvider(directory)
        assertNotNull(reopenedStorage.getLatestCheckpoint("checkpoint-case"))
        val resumedExecutor = ScriptedExecutor({ prompt ->
            val text = prompt.messages.joinToString { it.textContent() }
            assertTrue("original-request-marker" in text)
            assertFalse("new-turn-marker" in text)
            answer("Recovered the interrupted review")
        })
        val restored = reviewAgent(resumedExecutor, ops) {
            install(Persistence) { storage = reopenedStorage }
        }
        try {
            assertEquals("Recovered the interrupted review", restored.createSession("checkpoint-case").run("new-turn-marker"))
            assertEquals(1, ops.reads.get())
            assertEquals(1, resumedExecutor.prompts.size)
            println("NATIVE S3 CHECKPOINT: fresh agent/storage restored original input and post-tool execution point; supplied new input was not a follow-up turn; lookup count stayed 1")
        } finally { restored.close() }
    }

    @Test fun `native graph performs a real registered tool call and completes`() = runBlocking<Unit> {
        val ops = FixtureOperations()
        val executor = ScriptedExecutor({ lookupCall() }, { answer("R-1: documentation, based on policy-A") })
        val trace = mutableListOf<String>()
        val agent = reviewAgent(executor, ops) {
            install(EventHandler) {
                onAgentStarting { trace += "started" }
                onToolCallStarting { trace += "tool:${it.toolName}" }
                onToolCallCompleted { trace += "tool-completed" }
                onAgentCompleted { trace += "completed" }
            }
        }
        try {
            assertEquals("R-1: documentation, based on policy-A", agent.run("Review R-1"))
            assertEquals(1, ops.reads.get())
            assertEquals(2, executor.prompts.size)
            assertEquals(listOf("started", "tool:lookup_request", "tool-completed", "completed"), trace)
            println("NATIVE S1: $trace, actualLookupCount=${ops.reads.get()}")
        } finally { agent.close() }
    }

    @Test fun `native repeated session run does not imply conversation continuity`() = runBlocking<Unit> {
        val executor = ScriptedExecutor({ answer("first result") }, { answer("second result") })
        val agent = reviewAgent(executor, FixtureOperations())
        try {
            val session = agent.createSession("same-session")
            session.run("Remember marker-alpha")
            session.run("What did we decide?")
            val secondPrompt = executor.prompts[1].messages.joinToString { it.textContent() }
            assertFalse("marker-alpha" in secondPrompt)
            assertFalse("first result" in secondPrompt)
            println("NATIVE S3: same run-session ID rebuilt context; previous input/result absent")
        } finally { agent.close() }
    }

    @Test fun `native coroutine cancellation interrupts cooperative tool execution`() = runBlocking<Unit> {
        val ops = FixtureOperations().apply { lookupGate = CompletableDeferred() }
        val executor = ScriptedExecutor({ lookupCall() }, { answer("unexpected") })
        val agent = reviewAgent(executor, ops)
        try {
            val running = async { agent.run("Review R-1") }
            withTimeout(5000) { ops.lookupEntered.await() }
            running.cancelAndJoin()
            assertEquals(1, executor.prompts.size)
            assertEquals(0, ops.changes.get())
            println("NATIVE S4: cancelled during cooperative tool, no follow-up model call")
        } finally { agent.close() }
    }
}
