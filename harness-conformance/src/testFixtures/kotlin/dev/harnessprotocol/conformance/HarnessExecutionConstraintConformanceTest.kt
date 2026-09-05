package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

abstract class HarnessExecutionConstraintConformanceTest {
    protected abstract fun executionFixture(case: ExecutionCase): ExecutionConstraintFixture

    @Test fun `read only execution with deny all exposes no actual write`() = exercise(ExecutionCase.READ_ONLY)
    @Test fun `workspace and denied network upper bounds are not exceeded by actual effects`() = exercise(ExecutionCase.WORKSPACE_DENIED_NETWORK)
    @Test fun `allowing network does not widen the required filesystem boundary`() = exercise(ExecutionCase.WORKSPACE_ALLOWED_NETWORK)
    private fun exercise(case: ExecutionCase) = runBlocking<Unit> {
        executionFixture(case).use { f ->
            val session = f.harness.createSession(f.spec)
            val attempts = buildList {
                add(ExecutionAttempt.WRITE_WORKSPACE)
                add(ExecutionAttempt.WRITE_ADDITIONAL)
                add(ExecutionAttempt.WRITE_OUTSIDE)
                if (case != ExecutionCase.READ_ONLY) add(ExecutionAttempt.USE_NETWORK)
            }
            attempts.forEach { attempt ->
                val task = session.startTask(f.prepare(attempt))
                assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
            }
            val written = f.writtenTargets()
            val upperBound = if (case == ExecutionCase.READ_ONLY) emptySet() else setOf("workspace", "additional")
            assertTrue(written.all { it in upperBound }, "Actual writes exceeded the required boundary: $written")
            assertFalse("outside" in written)
            if (case == ExecutionCase.WORKSPACE_DENIED_NETWORK) assertEquals(0, f.networkRequests())
            // ALLOWED is an execution-environment ceiling. DenyAll may still reject this effect.
        }
    }
}
