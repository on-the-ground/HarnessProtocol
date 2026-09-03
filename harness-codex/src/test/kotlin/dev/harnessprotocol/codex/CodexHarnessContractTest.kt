package dev.harnessprotocol.codex

import dev.harnessprotocol.AgentHarness
import dev.harnessprotocol.AgentSpec
import dev.harnessprotocol.ApprovalPolicy
import dev.harnessprotocol.FilesystemAccess
import dev.harnessprotocol.NetworkAccess
import dev.harnessprotocol.testkit.AgentHarnessContractTest
import dev.harnessprotocol.testkit.Envelope.assertAbsent
import dev.harnessprotocol.testkit.Envelope.assertNullableString
import dev.harnessprotocol.testkit.Envelope.assertString
import dev.harnessprotocol.testkit.Envelope.assertStrings
import dev.harnessprotocol.testkit.Envelope.objects
import dev.harnessprotocol.testkit.Envelope.string
import dev.harnessprotocol.testkit.IntentProjection
import dev.harnessprotocol.testkit.ProviderFixture
import dev.harnessprotocol.testkit.RecordingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

class CodexHarnessContractTest : AgentHarnessContractTest() {
    override fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness =
        CodexHarness.usingBridge(bridge, scope)

    override fun projection() = IntentProjection { spec, sent ->
        sent.assertNullableString("instructions", spec.instructions)
        sent.assertNullableString("model", spec.model)
        sent.assertNullableString("workingDirectory", spec.workingDirectory)
        assertEquals(spec.skills.map { it.name to it.path }, sent.objects("skills").map { it.string("name") to it.string("path") })
        val fs = spec.executionPolicy.filesystem
        sent.assertString(
            "filesystem",
            when (fs) {
                FilesystemAccess.ProviderDefault -> "provider_default"
                FilesystemAccess.ReadOnly -> "read_only"
                is FilesystemAccess.WorkspaceWrite -> "workspace_write"
                FilesystemAccess.FullAccess -> "full_access"
            },
        )
        sent.assertStrings("additionalWritableRoots", (fs as? FilesystemAccess.WorkspaceWrite)?.additionalWritableRoots?.toList().orEmpty())
        // B3: a compatible spec never carries network intent outside workspace-write.
        if (spec.executionPolicy.network != NetworkAccess.PROVIDER_DEFAULT) {
            assertEquals(true, fs is FilesystemAccess.WorkspaceWrite, "network intent must have been rejected for $fs")
        }
        sent.assertString(
            "network",
            when (spec.executionPolicy.network) {
                NetworkAccess.PROVIDER_DEFAULT -> "provider_default"
                NetworkAccess.DENIED -> "denied"
                NetworkAccess.ALLOWED -> "allowed"
            },
        )
        sent.assertString(
            "approval",
            when (spec.executionPolicy.approval) {
                ApprovalPolicy.PROVIDER_DEFAULT -> "provider_default"
                ApprovalPolicy.DENY_ALL -> "deny_all"
                ApprovalPolicy.AGENT_REVIEWED -> "agent_reviewed"
                ApprovalPolicy.CALLER_DECIDES -> "caller_decides"
            },
        )
        sent.assertAbsent("metadata", "metadata was removed from the port")
    }

    override fun fixture() = object : ProviderFixture {
        override fun started() = listOf(notification("turn/started"))
        override fun messageDelta(text: String) = listOf(notification("item/agentMessage/delta", buildJsonObject { put("delta", text) }))
        override fun completed(finalText: String) = listOf(
            notification("item/completed", buildJsonObject {
                put("item", buildJsonObject {
                    put("id", "message-1")
                    put("type", "agentMessage")
                    put("text", finalText)
                    put("phase", "final_answer")
                })
            }),
            notification("turn/completed", buildJsonObject { put("turn", buildJsonObject { put("status", "completed") }) }),
        )
        override fun failed(message: String) = listOf(
            notification("error", buildJsonObject { put("error", buildJsonObject { put("message", message) }) }),
        )
        override fun cancelled() = listOf(
            notification("turn/completed", buildJsonObject { put("turn", buildJsonObject { put("status", "interrupted") }) }),
        )
    }

    override fun compatibleSpec() = AgentSpec()
}

internal fun notification(method: String, payload: JsonObject = JsonObject(emptyMap())) =
    buildJsonObject {
        put("method", method)
        put("payload", payload)
    }
