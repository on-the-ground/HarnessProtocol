package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.*
import kotlin.test.*

abstract class HarnessWorkspaceConformanceTest {
    protected abstract fun workspaceFixture(): WorkspaceFixture

    @Test fun `configured workspace and activated skill body reach each actual native task`() = runBlocking<Unit> {
        workspaceFixture().use { f ->
            val session = f.harness.createSession(f.spec)
            repeat(2) { index ->
                val before = f.observation.observedContexts.size
                val request = TaskRequest(TaskInput.Text("workspace-user-input-$index"))
                val outcome = withTimeout(60_000) { session.startTask(request).awaitOutcome() }
                assertIs<TaskOutcome.Completed>(outcome)
                assertEquals("workspace-user-input-$index", (request.input as TaskInput.Text).text)
                val actual = f.observation.observedContexts.drop(before).joinToString()
                assertTrue(f.activeSkillBody in actual, "Native model context must contain the loaded active skill body")
                assertFalse(f.inactiveSkillBody in actual, "Merely providing a skill must not force its body into the model context")
                // JSON transport escapes Windows separators; this is the actual model request, not session.spec.
                val unescaped = actual.replace("\\\\", "\\")
                assertTrue(unescaped.contains(f.workingDirectory), "Native context must identify the configured workspace")
                assertTrue(unescaped.contains(f.activeSkillPath) && f.activeSkillName in actual)
                assertTrue(unescaped.contains(f.inactiveSkillPath) && f.inactiveSkillName in actual,
                    "An inactive skill must remain available without applying its body")
                assertTrue("workspace-user-input-$index" in actual)
            }
        }
    }

    @Test fun `a missing skill artifact is rejected before native work`() = runBlocking<Unit> {
        workspaceFixture().use { f ->
            assertFalse(f.harness.validate(f.invalidSpec).isCompatible)
            assertFailsWith<IncompatibleRequirementException> { f.harness.createSession(f.invalidSpec) }
            assertTrue(f.observation.observedContexts.isEmpty())
        }
    }
}
