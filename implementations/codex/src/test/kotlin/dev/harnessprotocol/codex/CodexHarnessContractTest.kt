package dev.harnessprotocol.codex

import dev.harnessprotocol.*

import dev.harnessprotocol.testkit.SdkAdapterContractTest
import dev.harnessprotocol.testkit.Envelope.assertAbsent
import dev.harnessprotocol.testkit.Envelope.assertNullableString
import dev.harnessprotocol.testkit.Envelope.assertString
import dev.harnessprotocol.testkit.Envelope.assertStrings
import dev.harnessprotocol.testkit.Envelope.objects
import dev.harnessprotocol.testkit.Envelope.string
import dev.harnessprotocol.testkit.IntentProjection
import dev.harnessprotocol.testkit.ProviderFixture
import dev.harnessprotocol.testkit.RecordingBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexHarnessContractTest : SdkAdapterContractTest() {
    override fun harness(bridge: RecordingBridge, scope: CoroutineScope): AgentHarness =
        CodexHarness.usingBridge(bridge, scope, StorageNamespace("contract-${java.util.UUID.randomUUID()}"))

    override fun projection() = IntentProjection { spec, sent ->
        sent.assertNullableString("instructions", spec.instructions)
        sent.assertNullableString("model", spec.model)
        if (spec.requirements.retention == ContextRetentionRequirement.Ephemeral) sent.assertString("retention", "ephemeral")
        else sent.assertAbsent("retention", "provider retention was not constrained")
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

    @Test
    fun `Codex reasoning effort stays in adapter configuration and reaches the native session envelope`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope, reasoningEffort = "high").use { harness ->
                val session = harness.createSession(SessionSpec())
                assertEquals("high", bridge.paramsOf("create_session").single().string("reasoningEffort"))
                session.release()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `blank Codex reasoning effort is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            CodexSdkOptions(reasoningEffort = " ")
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            assertFailsWith<IllegalArgumentException> {
                CodexHarness.usingBridge(RecordingBridge(), scope, reasoningEffort = " ")
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `reasoning option discovery is model scoped and enables compatible preflight`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { reasoningCatalog("model-a") }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                assertIs<Support.Conditional>(harness.support[Capability.REASONING_OPTION_SELECTION])
                val discovery = assertIs<ReasoningOptionDiscovery>(harness)
                val catalog = discovery.reasoningOptions("model-a")
                assertEquals("model-a", catalog.model)
                assertEquals(listOf("low", "high"), catalog.options.map { it.id.value })
                assertEquals(ReasoningOptionId("low"), catalog.defaultOptionId)

                val session = harness.createSession(SessionSpec(model = "model-a"))
                val accepted = TaskRequest(
                    TaskInput.Text("reason"),
                    TaskRequirements(reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("high"))),
                )
                assertEquals(CompatibilityStatus.COMPATIBLE, session.validate(accepted).status)
                val rejected = accepted.copy(requirements = TaskRequirements(
                    reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("missing")),
                ))
                assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(rejected).status)
                assertFailsWith<IncompatibleRequirementException> { session.startTask(rejected) }
                assertTrue(bridge.paramsOf("start_execution").isEmpty())
                session.release()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `direct task admission refreshes reasoning options and preserves the selected option`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { reasoningCatalog("model-a") }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val session = harness.createSession(SessionSpec(model = "model-a"))
                val request = TaskRequest(
                    TaskInput.Text("reason"),
                    TaskRequirements(reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("high"))),
                )
                assertEquals(CompatibilityStatus.UNCONFIRMED, session.validate(request).status)
                val task = session.startTask(request)
                assertEquals(listOf("create_session", "list_reasoning_options", "start_execution"), bridge.methods)
                assertEquals("high", bridge.paramsOf("start_execution").single().string("reasoningOption"))
                fixture().completed("done").forEach { bridge.emit(it) }
                assertIs<TaskOutcome.Completed>(task.awaitOutcome())

                val nextRequest = request.copy(requirements = TaskRequirements(
                    reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("low")),
                ))
                val nextTask = session.startTask(nextRequest)
                assertEquals(
                    listOf("high", "low"),
                    bridge.paramsOf("start_execution").map { it.string("reasoningOption") },
                )
                fixture().completed("next").forEach { bridge.emit(it) }
                assertIs<TaskOutcome.Completed>(nextTask.awaitOutcome())
                session.release()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `stale reasoning discovery cannot authorize a removed option`() = kotlinx.coroutines.runBlocking {
        var observations = 0
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") {
                observations++
                if (observations == 1) reasoningCatalog("model-a") else buildJsonObject {
                    put("model", "model-a")
                    put("defaultOptionId", "low")
                    put("options", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "low")
                            put("displayName", "Low")
                        })
                    })
                }
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val discovery = assertIs<ReasoningOptionDiscovery>(harness)
                discovery.reasoningOptions("model-a")
                val session = harness.createSession(SessionSpec(model = "model-a"))
                val request = TaskRequest(
                    TaskInput.Text("reason"),
                    TaskRequirements(reasoning = ReasoningOptionRequirement.Selected("model-a", ReasoningOptionId("high"))),
                )
                assertEquals(CompatibilityStatus.COMPATIBLE, session.validate(request).status)
                assertFailsWith<IncompatibleRequirementException> { session.startTask(request) }
                assertEquals(2, observations)
                assertTrue(bridge.paramsOf("start_execution").isEmpty())
                session.release()
            }
        } finally { scope.cancel() }
    }

    @kotlin.test.Test
    fun `ephemeral retention is sent and observed rather than inferred from the request`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge().apply {
            respondTo("create_session") { buildJsonObject {
                put("sessionId", "ephemeral-1")
                put("retention", "ephemeral")
                put("historyVisibility", "unknown")
            } }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
                val session = harness.createSession(spec)
                assertEquals("ephemeral", bridge.paramsOf("create_session").single().string("retention"))
                assertEquals(ContextRetentionDisposition.EPHEMERAL, session.disposition.retention)
                assertEquals(UserHistoryVisibility.UNKNOWN, session.disposition.historyVisibility)
            }
        } finally { scope.cancel() }
    }

    @kotlin.test.Test
    fun `missing native ephemeral observation fails closed and releases the handle`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge().apply {
            respondTo("create_session") { buildJsonObject { put("sessionId", "unconfirmed-1") } }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
                val failure = assertFailsWith<RequirementUnconfirmedException> { harness.createSession(spec) }
                assertEquals("requirements.retention", failure.issues.single().path)
                assertTrue("discard_session" in bridge.methods)
            }
        } finally { scope.cancel() }
    }

    @kotlin.test.Test
    fun `contrary native retention observation fails closed`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge().apply {
            respondTo("create_session") { buildJsonObject {
                put("sessionId", "materialized-1")
                put("retention", "materialized")
            } }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
                assertFailsWith<RequirementUnconfirmedException> { harness.createSession(spec) }
                assertTrue("discard_session" in bridge.methods)
            }
        } finally { scope.cancel() }
    }

    @kotlin.test.Test
    fun `unconfirmed mismatch cleanup is retried when the harness closes`() = kotlinx.coroutines.runBlocking {
        var releaseAttempts = 0
        val bridge = RecordingBridge().apply {
            respondTo("create_session") { buildJsonObject {
                put("sessionId", "orphan-1")
                put("retention", "materialized")
            } }
            respondTo("discard_session") {
                releaseAttempts++
                if (releaseAttempts == 1) throw HarnessTransportException("lost cleanup acknowledgement")
                JsonObject(emptyMap())
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val harness = CodexHarness.usingBridge(bridge, scope)
        try {
            val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
            val failure = assertFailsWith<RequirementUnconfirmedException> { harness.createSession(spec) }
            assertTrue(failure.issues.any { it.path == "session.discard" })
            harness.close()
            assertEquals(2, releaseAttempts)
        } finally {
            harness.close()
            scope.cancel()
        }
    }

    @kotlin.test.Test
    fun `failed ephemeral release is retained as a close discard obligation`() = kotlinx.coroutines.runBlocking {
        var discardAttempts = 0
        val bridge = RecordingBridge().apply {
            respondTo("release_session") { throw HarnessTransportException("lost release acknowledgement") }
            respondTo("discard_session") {
                discardAttempts++
                JsonObject(emptyMap())
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val harness = CodexHarness.usingBridge(bridge, scope)
        try {
            val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
            harness.createSession(spec).release()
            assertEquals(0, discardAttempts)
            harness.close()
            assertEquals(1, discardAttempts)
        } finally {
            harness.close()
            scope.cancel()
        }
    }

    @kotlin.test.Test
    fun `timed out ephemeral release remains a close discard obligation`() = kotlinx.coroutines.runBlocking {
        var discardAttempts = 0
        val bridge = RecordingBridge().apply {
            respondTo("release_session") { kotlinx.coroutines.awaitCancellation() }
            respondTo("discard_session") {
                discardAttempts++
                JsonObject(emptyMap())
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val harness = CodexHarness.usingBridge(bridge, scope)
        try {
            val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
            harness.createSession(spec).release()
            assertEquals(0, discardAttempts)
            harness.close()
            assertEquals(1, discardAttempts)
        } finally {
            harness.close()
            scope.cancel()
        }
    }

    @kotlin.test.Test
    fun `concurrent release and close delete an ephemeral session only once`() = kotlinx.coroutines.runBlocking {
        val releaseEntered = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val bridge = RecordingBridge().apply {
            respondTo("release_session") {
                releaseEntered.complete(Unit)
                allowRelease.await()
                JsonObject(emptyMap())
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val harness = CodexHarness.usingBridge(bridge, scope)
        try {
            val spec = SessionSpec(requirements = SessionRequirements(retention = ContextRetentionRequirement.Ephemeral))
            val session = harness.createSession(spec)
            val releasing = async { session.release() }
            releaseEntered.await()
            val closing = async(Dispatchers.Default) { harness.close() }
            yield()
            allowRelease.complete(Unit)
            releasing.await()
            closing.await()
            assertEquals(1, bridge.methods.count { it == "release_session" })
            assertEquals(0, bridge.methods.count { it == "discard_session" })
        } finally {
            allowRelease.complete(Unit)
            harness.close()
            scope.cancel()
        }
    }

    @kotlin.test.Test
    fun `hidden account history remains unconfirmed before native creation`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(
                    historyVisibility = UserHistoryVisibilityRequirement.Hidden,
                ))
                assertIs<Support.Unknown>(harness.support[Capability.USER_HISTORY_VISIBILITY])
                assertEquals(CompatibilityStatus.UNCONFIRMED, harness.validate(spec).status)
                assertFailsWith<RequirementUnconfirmedException> { harness.createSession(spec) }
                assertTrue(bridge.paramsOf("create_session").isEmpty())
            }
        } finally { scope.cancel() }
    }

    @kotlin.test.Test
    fun `ephemeral retention cannot be combined with persistent reopen`() = kotlinx.coroutines.runBlocking {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope, StorageNamespace("test")).use { harness ->
                val spec = SessionSpec(requirements = SessionRequirements(
                    persistence = PersistenceRequirement.Required(),
                    retention = ContextRetentionRequirement.Ephemeral,
                ))
                assertFailsWith<IncompatibleRequirementException> { harness.createSession(spec) }
                assertTrue(bridge.paramsOf("create_session").isEmpty())
            }
        } finally { scope.cancel() }
    }
}

private fun reasoningCatalog(model: String) = buildJsonObject {
    put("model", model)
    put("defaultOptionId", "low")
    put("options", buildJsonArray {
        add(buildJsonObject {
            put("id", "low")
            put("displayName", "Low")
            put("description", "Fast")
        })
        add(buildJsonObject {
            put("id", "high")
            put("displayName", "High")
            put("description", "Deep")
        })
    })
}

class CodexSdkOptionsTest {
    @Test
    fun `specific Codex executable is passed only through the explicit option`() {
        val environment = CodexSdkOptions(
            environment = mapOf("EXISTING" to "value", CODEX_EXECUTABLE_ENV to "ignored"),
            codexExecutable = Path.of("runtime", "codex"),
        ).hostEnvironment()

        assertEquals("value", environment["EXISTING"])
        assertEquals(
            Path.of("runtime", "codex").toAbsolutePath().normalize().toString(),
            environment[CODEX_EXECUTABLE_ENV],
        )
    }

    @Test
    fun `omitted Codex executable preserves the SDK bundled runtime`() {
        assertEquals(
            mapOf("EXISTING" to "value"),
            CodexSdkOptions(
                environment = mapOf("EXISTING" to "value", CODEX_EXECUTABLE_ENV to "ignored"),
            ).hostEnvironment(),
        )
    }
}

internal fun notification(method: String, payload: JsonObject = JsonObject(emptyMap())) =
    buildJsonObject {
        put("method", method)
        put("payload", payload)
    }
