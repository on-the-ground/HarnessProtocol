package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

abstract class HarnessContextConformanceTest {
    protected abstract fun contextFixture(): ContextFixture
    private fun text(value: String) = TaskRequest(TaskInput.Text(value))
    private suspend fun completed(session: AgentSession, value: String) =
        assertIs<TaskOutcome.Completed>(withTimeout(60_000) { session.startTask(text(value)).awaitOutcome() })

    @Test fun `reopened aliases share active work exclusion and retain released handle boundaries`() = runBlocking<Unit> {
        contextFixture().use { f ->
            val original = f.harness.createSession(f.spec)
            completed(original, "persist-before-alias")
            val ref = assertNotNull(original.persistentRef)
            val alias = (f.harness as PersistentSessions).reopenSession(ref, f.spec)
            assertEquals(original.id, alias.id)
            f.observation.hold()
            val task = alias.startTask(text("active-via-alias"))
            val before = f.start.observedSubmissions()
            assertFailsWith<IllegalStateException> { original.startTask(text("overlap-via-original")) }
            assertFailsWith<IllegalStateException> { (f.harness as PersistentSessions).reopenSession(ref, f.spec) }
            assertEquals(before, f.start.observedSubmissions())
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            original.release()
            assertFailsWith<SessionBlockedException> { original.startTask(text("released-handle")) }
            completed(alias, "alias-still-usable")
            assertTrue(f.observation.observedContexts.last().contains("persist-before-alias"))
        }
    }

    @Test fun `unconfirmed start blocks every alias and recreation without blocking a fresh context`() = runBlocking<Unit> {
        contextFixture().use { f ->
            val original = f.harness.createSession(f.spec)
            completed(original, "persist-before-loss")
            val ref = assertNotNull(original.persistentRef)
            val alias = (f.harness as PersistentSessions).reopenSession(ref, f.spec)
            f.start.loseAcceptanceAcknowledgement(false)
            assertFailsWith<TaskStartUnconfirmedException> { alias.startTask(text("lost-start")) }
            val before = f.start.observedSubmissions()
            assertFailsWith<SessionBlockedException> { original.startTask(text("alias-retry")) }
            assertFailsWith<SessionBlockedException> { (f.harness as PersistentSessions).reopenSession(ref, f.spec) }
            original.release()
            alias.release()
            assertEquals(before, f.start.observedSubmissions())
            f.harness.close()
            f.recreateHarness().use { recreated ->
                assertFailsWith<SessionBlockedException> { (recreated as PersistentSessions).reopenSession(ref, f.spec) }
                completed(recreated.createSession(f.spec), "independent-after-recreation")
                assertFalse(f.observation.observedContexts.last().contains("persist-before-loss"))
            }
        }
    }

    @Test fun `release of one active context leaves another native context progressing`() = runBlocking<Unit> {
        contextFixture().use { f ->
            val first = f.harness.createSession(f.spec)
            val second = f.harness.createSession(f.spec)
            f.observation.hold()
            val a = first.startTask(text("release-this-context"))
            val b = second.startTask(text("retain-this-context"))
            withTimeout(60_000) { while (f.observation.observedContexts.none { "retain-this-context" in it }) delay(10) }
            first.release()
            assertTrue(withTimeout(5_000) { a.awaitOutcome() } is TaskOutcome.Cancelled)
            assertFalse(b.state.value.isTerminal)
            f.observation.release()
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { b.awaitOutcome() })
            assertTrue(f.observation.observedContexts.filter { "retain-this-context" in it }.all { "release-this-context" !in it })
        }
    }
}
