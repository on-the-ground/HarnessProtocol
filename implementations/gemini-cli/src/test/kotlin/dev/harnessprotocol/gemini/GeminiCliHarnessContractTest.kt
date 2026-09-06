package dev.harnessprotocol.gemini

import dev.harnessprotocol.*
import kotlin.test.assertNull

import dev.harnessprotocol.testkit.SdkAdapterContractTest
import dev.harnessprotocol.testkit.Envelope.assertAbsent
import dev.harnessprotocol.testkit.Envelope.assertNullableString
import dev.harnessprotocol.testkit.Envelope.objects
import dev.harnessprotocol.testkit.Envelope.string
import dev.harnessprotocol.testkit.IntentProjection
import dev.harnessprotocol.testkit.ProviderFixture
import dev.harnessprotocol.testkit.RecordingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GeminiCliHarnessContractTest : SdkAdapterContractTest() {
    override fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness =
        GeminiCliHarness.usingBridge(bridge, scope, StorageNamespace("contract-${java.util.UUID.randomUUID()}"))

    override fun projection() = IntentProjection { spec, sent ->
        sent.assertNullableString("instructions", spec.instructions)
        sent.assertNullableString("model", spec.model)
        sent.assertNullableString("workingDirectory", (spec.requirements.workspace as? WorkspaceRequirement.Required)?.workingDirectory)
        val skills = (spec.requirements.workspace as? WorkspaceRequirement.Required)?.skills.orEmpty()
        assertEquals(skills.map { it.name to it.path }, sent.objects("skills").map { it.string("name") to it.string("path") })
        assertEquals(skills.map { it.activate.toString() }, sent.objects("skills").map { it.string("activate") })
        if (spec.requirements.workspace == WorkspaceRequirement.NotRequired) sent.assertAbsent("skills", "workspace was not required")
        // The Gemini SDK exposes no policy surface. validate() rejects every non-default
        // policy, so a compatible spec is always provider-default and nothing is sent.
        assertEquals(null, (spec.requirements.execution as? ExecutionConstraint.Required)?.filesystem)
        assertEquals(null, (spec.requirements.execution as? ExecutionConstraint.Required)?.network)
        assertEquals(ApprovalRequirement.ProviderDefault, spec.requirements.approval)
        sent.assertAbsent("filesystem", "no policy surface")
        sent.assertAbsent("network", "no policy surface")
        sent.assertAbsent("approval", "no policy surface")
        sent.assertAbsent("metadata", "metadata was removed from the port")
    }

    override fun fixture() = object : ProviderFixture {
        override fun started() = listOf(buildJsonObject { put("type", "execution_started") })
        override fun messageDelta(text: String) = listOf(buildJsonObject { put("type", "content"); put("value", text) })
        override fun completed(finalText: String) = listOf(
            buildJsonObject { put("type", "content"); put("value", finalText) },
            buildJsonObject { put("type", "execution_completed") },
        )
        override fun failed(message: String) = listOf(
            buildJsonObject { put("type", "execution_failed"); put("value", buildJsonObject { put("message", message) }) },
        )
        override fun cancelled() = listOf(buildJsonObject { put("type", "execution_cancelled") })
    }

    override fun compatibleSpec() = SessionSpec()
}

class GeminiCliPolicyTest {
    @Test
    fun `caller decides is rejected where unsupported`() {
        val harness = GeminiCliHarness.usingBridge(RecordingBridge())
        val report = harness.validate(
            SessionSpec(requirements = SessionRequirements(approval = ApprovalRequirement.CallerDecides)),
        )
        kotlin.test.assertFalse(report.isCompatible)
        assertEquals("requirements.approval", report.issues.single().path)
    }

    @Test
    fun `reasoning option is rejected before a Gemini task starts`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge()
        GeminiCliHarness.usingBridge(bridge).use { harness ->
            assertIs<Support.Unsupported>(harness.support[Capability.REASONING_OPTION_SELECTION])
            val session = harness.createSession(SessionSpec())
            val request = TaskRequest(
                TaskInput.Text("reason"),
                TaskRequirements(reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("high"))),
            )
            assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(request).status)
            assertFailsWith<IncompatibleRequirementException> { session.startTask(request) }
            kotlin.test.assertTrue(bridge.paramsOf("start_execution").isEmpty())
        }
    }
}
