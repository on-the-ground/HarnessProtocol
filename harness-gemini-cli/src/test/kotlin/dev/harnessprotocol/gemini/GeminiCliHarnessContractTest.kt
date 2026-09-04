package dev.harnessprotocol.gemini

import dev.harnessprotocol.legacy.AgentHarness
import dev.harnessprotocol.legacy.AgentSpec
import dev.harnessprotocol.legacy.ApprovalPolicy
import dev.harnessprotocol.legacy.FilesystemAccess
import dev.harnessprotocol.legacy.NetworkAccess
import dev.harnessprotocol.testkit.AgentHarnessContractTest
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

class GeminiCliHarnessContractTest : AgentHarnessContractTest() {
    override fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness =
        GeminiCliHarness.usingBridge(bridge, scope)

    override fun projection() = IntentProjection { spec, sent ->
        sent.assertNullableString("instructions", spec.instructions)
        sent.assertNullableString("model", spec.model)
        sent.assertNullableString("workingDirectory", spec.workingDirectory)
        assertEquals(spec.skills.map { it.name to it.path }, sent.objects("skills").map { it.string("name") to it.string("path") })
        // The Gemini SDK exposes no policy surface. validate() rejects every non-default
        // policy, so a compatible spec is always provider-default and nothing is sent.
        assertEquals(FilesystemAccess.ProviderDefault, spec.executionPolicy.filesystem)
        assertEquals(NetworkAccess.PROVIDER_DEFAULT, spec.executionPolicy.network)
        assertEquals(ApprovalPolicy.PROVIDER_DEFAULT, spec.executionPolicy.approval)
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

    override fun compatibleSpec() = AgentSpec()
}

class GeminiCliPolicyTest {
    @Test
    fun `caller decides is rejected where unsupported`() {
        val harness = GeminiCliHarness.usingBridge(RecordingBridge())
        val report = harness.validate(
            AgentSpec(executionPolicy = dev.harnessprotocol.legacy.ExecutionPolicy(approval = ApprovalPolicy.CALLER_DECIDES)),
        )
        kotlin.test.assertFalse(report.isCompatible)
        assertEquals("executionPolicy.approval", report.issues.single().path)
    }
}
