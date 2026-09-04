package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

abstract class HarnessPersistenceFailureConformanceTest {
    protected abstract fun persistenceFixture(): PersistenceFailureFixture
    private fun request(value: String) = TaskRequest(TaskInput.Text(value))

    @Test fun `changed configuration cannot silently replace a live handles declared configuration`() = runBlocking<Unit> {
        persistenceFixture().use { f ->
            val original = f.harness.createSession(f.spec)
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { original.startTask(request("before-config-change")).awaitOutcome() })
            val ref = assertNotNull(original.persistentRef)
            val desired = f.spec.copy(instructions = "AHP_CHANGED_CONFIGURATION")
            val storage = f.harness as PersistentSessions
            assertFailsWith<IncompatibleRequirementException> { storage.reopenSession(ref, desired) }
            val before = f.observation.observedContexts.size
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { original.startTask(request("original-config-still-live")).awaitOutcome() })
            val actual = f.observation.observedContexts.drop(before).joinToString()
            assertTrue(assertNotNull(f.spec.instructions) in actual)
            assertFalse("AHP_CHANGED_CONFIGURATION" in actual)
            original.release()
            if (f.supportsChangedInstructions) {
                val updated = storage.reopenSession(ref, desired)
                val after = f.observation.observedContexts.size
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) { updated.startTask(request("updated-after-release")).awaitOutcome() })
                assertTrue(f.observation.observedContexts.drop(after).any { "AHP_CHANGED_CONFIGURATION" in it })
            } else assertFailsWith<IncompatibleRequirementException> { storage.reopenSession(ref, desired) }
        }
    }

    @Test fun `unknown persistent reference is rejected without creating replacement context`() = runBlocking<Unit> {
        persistenceFixture().use { f ->
            val session = f.harness.createSession(f.spec)
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { session.startTask(request("known-context")).awaitOutcome() })
            val ref = assertNotNull(session.persistentRef)
            val before = f.observation.observedContexts.size
            val failure = assertFails { (f.harness as PersistentSessions).reopenSession(ref.copy(id = "00000000-0000-0000-0000-000000000001"), f.spec) }
            assertTrue(failure is HarnessTransportException || failure is IncompatibleRequirementException, "$failure")
            assertEquals(before, f.observation.observedContexts.size)
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { session.startTask(request("known-still-usable")).awaitOutcome() })
        }
    }

    @Test fun `unavailable native history fails reopen and restoration recovers the original context`() = runBlocking<Unit> {
        persistenceFixture().use { f ->
            val original = f.harness.createSession(f.spec)
            assertIs<TaskOutcome.Completed>(withTimeout(60_000) { original.startTask(request("restore-original-marker")).awaitOutcome() })
            val ref = assertNotNull(original.persistentRef)
            original.release()
            f.harness.close()
            f.hideStoredContext()
            f.recreateHarness().use { unavailable ->
                val before = f.observation.observedContexts.size
                assertFailsWith<HarnessTransportException> { (unavailable as PersistentSessions).reopenSession(ref, f.spec) }
                assertEquals(before, f.observation.observedContexts.size)
            }
            f.restoreStoredContext()
            f.recreateHarness().use { restored ->
                val session = (restored as PersistentSessions).reopenSession(ref, f.spec)
                val before = f.observation.observedContexts.size
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) { session.startTask(request("after-storage-restored")).awaitOutcome() })
                assertTrue(f.observation.observedContexts.drop(before).any { "restore-original-marker" in it })
                assertEquals(ref, session.persistentRef)
            }
        }
    }
}
