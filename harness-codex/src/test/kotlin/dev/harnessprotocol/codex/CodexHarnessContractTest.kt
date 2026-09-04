package dev.harnessprotocol.codex

import dev.harnessprotocol.*
import kotlin.test.assertNull

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
        CodexHarness.usingBridge(bridge, scope, StorageNamespace("contract-${java.util.UUID.randomUUID()}"))

    override fun projection() = IntentProjection { spec, sent ->
        sent.assertNullableString("instructions", spec.instructions)
        sent.assertNullableString("model", spec.model)
        sent.assertNullableString("workingDirectory", (spec.requirements.workspace as? WorkspaceRequirement.Required)?.workingDirectory)
        val skills = (spec.requirements.workspace as? WorkspaceRequirement.Required)?.skills.orEmpty()
        assertEquals(skills.map { it.name to it.path }, sent.objects("skills").map { it.string("name") to it.string("path") })
        assertEquals(skills.map { it.activate.toString() }, sent.objects("skills").map { it.string("activate") })
        if (spec.requirements.workspace == WorkspaceRequirement.NotRequired) sent.assertAbsent("skills", "workspace was not required")
        val fs = (spec.requirements.execution as? ExecutionConstraint.Required)?.filesystem
        sent.assertString(
            "filesystem",
            when (fs) {
                null -> "provider_default"
                FilesystemAccess.ReadOnly -> "read_only"
                is FilesystemAccess.WorkspaceWrite -> "workspace_write"
                FilesystemAccess.FullAccess -> "full_access"
            },
        )
        if (fs is FilesystemAccess.WorkspaceWrite) sent.assertStrings("additionalWritableRoots", fs.additionalWritableRoots.toList())
        else sent.assertAbsent("additionalWritableRoots", "no workspace-write override")
        // B3: a compatible spec never carries network intent outside workspace-write.
        if ((spec.requirements.execution as? ExecutionConstraint.Required)?.network != null) {
            assertEquals(true, fs is FilesystemAccess.WorkspaceWrite, "network intent must have been rejected for $fs")
        }
        sent.assertString(
            "network",
            when ((spec.requirements.execution as? ExecutionConstraint.Required)?.network) {
                null -> "provider_default"
                NetworkAccess.DENIED -> "denied"
                NetworkAccess.ALLOWED -> "allowed"
            },
        )
        sent.assertString(
            "approval",
            when (spec.requirements.approval) {
                ApprovalRequirement.ProviderDefault -> "provider_default"
                ApprovalRequirement.DenyAll -> "deny_all"
                ApprovalRequirement.AgentReviewed -> "agent_reviewed"
                ApprovalRequirement.CallerDecides -> "caller_decides"
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

    override fun compatibleSpec() = SessionSpec()
}

internal fun notification(method: String, payload: JsonObject = JsonObject(emptyMap())) =
    buildJsonObject {
        put("method", method)
        put("payload", payload)
    }
