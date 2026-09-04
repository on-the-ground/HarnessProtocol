package dev.harnessprotocol.conformance

import dev.harnessprotocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/**
 * Support, preflight and admission have independent expectations. Every declared case runs
 * both with preflight and directly on a fresh harness. No profile name or capability set
 * decides whether to skip a test. Acceptance alone is not evidence of policy enforcement.
 */
abstract class HarnessRequirementsConformanceTest {
    protected abstract fun requirementFixture(): RuntimeRequirementsFixture

    @TestFactory
    fun `declared requirements match support validation and actual admission`(): List<DynamicTest> {
        val (provider, profiles) = requirementFixture().use { fixture ->
            fixture.provider to fixture.profiles().also(::checkProfiles)
        }
        return profiles.flatMap { profile ->
            val prefix = "${provider.value}/${profile.id}"
            listOf(DynamicTest.dynamicTest("$prefix/support") {
                requirementFixture().use { fixture ->
                    assertEquals(provider, fixture.provider)
                    val declared = fixture.profiles().single { it.id == profile.id }
                    fixture.createHarness(profile.id).use { h ->
                        assertEquals(provider, h.provider)
                        Capability.entries.forEach { capability ->
                            assertSupport(declared.expectedSupport[capability], h.support[capability], capability)
                        }
                    }
                }
            }) + profile.cases.flatMap { case ->
                listOf(true, false).map { preflight ->
                    DynamicTest.dynamicTest("$prefix/${case.id}/${if (preflight) "preflight" else "direct"}") {
                        requirementFixture().use { fixture ->
                            assertEquals(provider, fixture.provider)
                            // Resource paths may be fresh; identity and expectations are declared independently.
                            val current = fixture.profiles().single { it.id == profile.id }.cases.single { it.id == case.id }
                            assertEquals(case.sessionValidation, current.sessionValidation)
                            assertEquals(case.taskValidation, current.taskValidation)
                            assertEquals(case.createDecision, current.createDecision)
                            assertEquals(case.startDecision, current.startDecision)
                            runBlocking { withTimeout(90_000) { checkAdmission(fixture, profile.id, current, preflight) } }
                        }
                    }
                }
            }
        }
    }

    private fun checkProfiles(profiles: List<FixtureProfile>) {
        assertTrue(profiles.isNotEmpty(), "No profiles were declared")
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size, "Duplicate profile identities")
        profiles.forEach { profile ->
            assertEquals(Capability.entries.toSet(), profile.expectedSupport.entries.keys,
                "Declare every support expectation explicitly, including Unknown: ${profile.id}")
            assertTrue(profile.cases.any {
                it.sessionSpec.requirements == SessionRequirements() && it.request.requirements == TaskRequirements() &&
                    it.createDecision == CompatibilityStatus.COMPATIBLE && it.startDecision == CompatibilityStatus.COMPATIBLE
            }, "Every profile must demonstrate normal task admission: ${profile.id}")
        }
    }

    private fun assertSupport(expected: Support, actual: Support, capability: Capability) {
        assertEquals(expected::class, actual::class, "$capability support kind")
        when (actual) {
            is Support.Conditional -> {
                assertEquals((expected as Support.Conditional).scope, actual.scope, "$capability condition scope")
                assertTrue(actual.condition.isNotBlank(), "$capability must explain its condition")
            }
            is Support.Unknown -> assertTrue(actual.reason.isNotBlank())
            is Support.Unsupported -> assertTrue(actual.reason.isNotBlank())
            Support.Supported -> Unit
        }
    }

    private suspend fun checkAdmission(fixture: RuntimeRequirementsFixture, profileId: String, case: RequirementCase, preflight: Boolean) {
        fixture.createHarness(profileId).use { h ->
            val before = fixture.observation.observedContexts.size
            if (preflight) assertEquals(case.sessionValidation, h.validate(case.sessionSpec).status, "session preflight")
            assertEquals(before, fixture.observation.observedContexts.size, "Session preflight must not invoke the model")
            val session = decide(case.createDecision) { h.createSession(case.sessionSpec) }
            if (session == null) {
                assertEquals(before, fixture.observation.observedContexts.size, "Rejected creation reached the model")
                return
            }
            try {
                assertEquals(before, fixture.observation.observedContexts.size, "Session creation must not start the task")
                if (preflight) assertEquals(case.taskValidation, session.validate(case.request).status, "task preflight")
                assertEquals(before, fixture.observation.observedContexts.size, "Task preflight must not invoke the model")
                val task = decide(case.startDecision) { session.startTask(case.request) }
                if (task == null) {
                    assertEquals(before, fixture.observation.observedContexts.size, "Rejected task reached the model")
                    // A pre-admission rejection must not poison the reusable session.
                    val retry = session.startTask(TaskRequest(TaskInput.Text("after-rejection-${case.id}")))
                    assertIs<TaskOutcome.Completed>(withTimeout(60_000) { retry.awaitOutcome() })
                    assertTrue(fixture.observation.observedContexts.drop(before).any { "after-rejection-${case.id}" in it })
                } else {
                    assertIs<TaskOutcome.Completed>(withTimeout(60_000) { task.awaitOutcome() })
                    assertEquals(TaskState.COMPLETED, task.state.value)
                    val input = (case.request.input as TaskInput.Text).text
                    assertTrue(fixture.observation.observedContexts.drop(before).any { input in it }, "Accepted input never reached the real runtime")
                    if (case.sessionSpec.requirements.persistence is PersistenceRequirement.Required) {
                        assertIs<PersistentSessions>(h)
                        assertNotNull(session.persistentRef)
                    }
                    if (case.sessionSpec.requirements.diagnostics is DiagnosticsRequirement.Required) assertIs<TaskDiagnostics>(task)
                }
            } finally { session.release() }
        }
    }

    private suspend fun <T> decide(expected: CompatibilityStatus, call: suspend () -> T): T? = when (expected) {
        CompatibilityStatus.COMPATIBLE -> call()
        CompatibilityStatus.INCOMPATIBLE -> {
            val error = assertFailsWith<IncompatibleRequirementException> { call() }
            assertEquals(CompatibilityStatus.INCOMPATIBLE, CompatibilityReport(error.issues).status)
            null
        }
        CompatibilityStatus.UNCONFIRMED -> {
            val error = assertFailsWith<RequirementUnconfirmedException> { call() }
            assertEquals(CompatibilityStatus.UNCONFIRMED, CompatibilityReport(error.issues).status)
            null
        }
    }
}
