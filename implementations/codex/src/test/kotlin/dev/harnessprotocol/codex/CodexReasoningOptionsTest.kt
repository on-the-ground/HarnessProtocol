package dev.harnessprotocol.codex

import dev.harnessprotocol.CompatibilityStatus
import dev.harnessprotocol.IncompatibleRequirementException
import dev.harnessprotocol.RequirementUnconfirmedException
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.TaskInput
import dev.harnessprotocol.TaskOutcome
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.testkit.Envelope.string
import dev.harnessprotocol.testkit.RecordingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexReasoningOptionsTest {
    private fun withHarness(
        bridge: RecordingBridge = RecordingBridge(),
        block: suspend (RecordingBridge, CodexHarness) -> Unit,
    ) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            CodexHarness.usingBridge(bridge, scope).use { block(bridge, it) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Codex reasoning catalog preserves canonical model and alias`() {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { params ->
                if (params["model"]?.jsonPrimitive?.contentOrNull == "missing") {
                    buildJsonObject { put("found", false) }
                } else {
                    reasoningCatalog()
                }
            }
        }
        withHarness(bridge) { _, harness ->
            val lookup = harness.reasoningOptions("model-a-alias")
            val catalog = assertIs<CodexReasoningOptionLookup.Available>(lookup).catalog
            assertEquals(CodexModelId("model-a-canonical"), catalog.model.canonicalModel)
            assertEquals(setOf("model-a-canonical", "model-a-alias"), catalog.model.aliases)
            assertTrue(catalog.model.matches("model-a-alias"))
            assertEquals(listOf("low", "high"), catalog.options.map { it.id.value })
            assertEquals(CodexReasoningOptionId("low"), catalog.defaultOptionId)

            assertEquals(
                CodexReasoningOptionLookup.NotFound("missing"),
                harness.reasoningOptions("missing"),
            )
        }
    }

    @Test
    fun `Codex Task option accepts canonical model and confirmed SDK alias`() {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { reasoningCatalog() }
        }
        withHarness(bridge) { _, harness ->
            val catalog = assertIs<CodexReasoningOptionLookup.Available>(
                harness.reasoningOptions("model-a-alias"),
            ).catalog
            val session = harness.createCodexSession(SessionSpec(model = "model-a-alias"))
            val selection = CodexReasoningOptionSelection(
                catalog.model.canonicalModel,
                CodexReasoningOptionId("high"),
            )
            val options = CodexTaskOptions(selection)
            val request = TaskRequest(TaskInput.Text("reason"))
            assertEquals(CompatibilityStatus.COMPATIBLE, session.validate(request, options).status)
            val task = session.startTask(request, options)
            assertEquals("high", bridge.paramsOf("start_execution").single().string("reasoningOption"))
            completedEvents("done").forEach { bridge.emit(it) }
            assertIs<TaskOutcome.Completed>(task.awaitOutcome())

            val next = session.startTask(
                TaskRequest(TaskInput.Text("next")),
                CodexTaskOptions(selection.copy(optionId = CodexReasoningOptionId("low"))),
            )
            assertEquals(
                listOf("high", "low"),
                bridge.paramsOf("start_execution").map { it.string("reasoningOption") },
            )
            completedEvents("next-done").forEach { bridge.emit(it) }
            assertIs<TaskOutcome.Completed>(next.awaitOutcome())
            session.release()
        }
    }

    @Test
    fun `provider default model is unconfirmed rather than a false mismatch`() {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { reasoningCatalog() }
        }
        withHarness(bridge) { _, harness ->
            val catalog = assertIs<CodexReasoningOptionLookup.Available>(
                harness.reasoningOptions(),
            ).catalog
            val session = harness.createCodexSession(SessionSpec(model = null))
            val options = CodexTaskOptions(CodexReasoningOptionSelection(
                catalog.model.canonicalModel,
                CodexReasoningOptionId("high"),
            ))
            val request = TaskRequest(TaskInput.Text("reason"))
            assertEquals(CompatibilityStatus.UNCONFIRMED, session.validate(request, options).status)
            assertFailsWith<RequirementUnconfirmedException> {
                session.startTask(request, options)
            }
            assertTrue(bridge.paramsOf("start_execution").isEmpty())
            session.release()
        }
    }

    @Test
    fun `confirmed different model is incompatible while unknown alias remains unconfirmed`() {
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") { params ->
                when (params["model"]?.jsonPrimitive?.contentOrNull) {
                    "model-b-alias" -> reasoningCatalog(
                        canonical = "model-b-canonical",
                        alias = "model-b-alias",
                        options = listOf("low"),
                    )
                    "unknown-alias", "missing" -> buildJsonObject { put("found", false) }
                    else -> reasoningCatalog()
                }
            }
        }
        withHarness(bridge) { _, harness ->
            val selectedCatalog = assertIs<CodexReasoningOptionLookup.Available>(
                harness.reasoningOptions("model-a-alias"),
            ).catalog
            harness.reasoningOptions("model-b-alias")
            val selection = CodexReasoningOptionSelection(
                selectedCatalog.model.canonicalModel,
                CodexReasoningOptionId("high"),
            )
            val options = CodexTaskOptions(selection)
            val request = TaskRequest(TaskInput.Text("reason"))
            val different = harness.createCodexSession(SessionSpec(model = "model-b-alias"))
            assertEquals(CompatibilityStatus.INCOMPATIBLE, different.validate(request, options).status)
            assertFailsWith<IncompatibleRequirementException> {
                different.startTask(request, options)
            }
            different.release()

            val unknown = harness.createCodexSession(SessionSpec(model = "unknown-alias"))
            assertEquals(CompatibilityStatus.UNCONFIRMED, unknown.validate(request, options).status)
            assertFailsWith<RequirementUnconfirmedException> {
                unknown.startTask(request, options)
            }
            unknown.release()
            assertTrue(bridge.paramsOf("start_execution").isEmpty())
        }
    }

    @Test
    fun `stale Codex catalog cannot authorize a removed option`() {
        var observations = 0
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") {
                observations++
                if (observations == 1) reasoningCatalog() else reasoningCatalog(options = listOf("low"))
            }
        }
        withHarness(bridge) { _, harness ->
            val catalog = assertIs<CodexReasoningOptionLookup.Available>(
                harness.reasoningOptions("model-a-canonical"),
            ).catalog
            val session = harness.createCodexSession(SessionSpec(model = "model-a-canonical"))
            val options = CodexTaskOptions(CodexReasoningOptionSelection(
                catalog.model.canonicalModel,
                CodexReasoningOptionId("high"),
            ))
            val request = TaskRequest(TaskInput.Text("reason"))
            assertEquals(CompatibilityStatus.COMPATIBLE, session.validate(request, options).status)
            assertFailsWith<IncompatibleRequirementException> {
                session.startTask(request, options)
            }
            assertEquals(2, observations)
            assertTrue(bridge.paramsOf("start_execution").isEmpty())
            session.release()
        }
    }

    @Test
    fun `latest missing model invalidates its cached catalog before preflight`() {
        var available = true
        val bridge = RecordingBridge().apply {
            respondTo("list_reasoning_options") {
                if (available) reasoningCatalog() else buildJsonObject { put("found", false) }
            }
        }
        withHarness(bridge) { _, harness ->
            val catalog = assertIs<CodexReasoningOptionLookup.Available>(
                harness.reasoningOptions("model-a-canonical"),
            ).catalog
            val session = harness.createCodexSession(SessionSpec(model = "model-a-alias"))
            val options = CodexTaskOptions(CodexReasoningOptionSelection(
                catalog.model.canonicalModel,
                CodexReasoningOptionId("high"),
            ))
            val request = TaskRequest(TaskInput.Text("reason"))
            assertEquals(CompatibilityStatus.COMPATIBLE, session.validate(request, options).status)

            available = false
            assertEquals(
                CodexReasoningOptionLookup.NotFound("model-a-canonical"),
                harness.reasoningOptions("model-a-canonical"),
            )
            assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(request, options).status)
            session.release()
        }
    }

    @Test
    fun `Codex reasoning option public values enforce model scoped invariants`() {
        val model = CodexModelIdentity(CodexModelId("model-a"), setOf("model-a-alias"))
        val low = CodexReasoningOptionDescriptor(CodexReasoningOptionId("low"), "Low")
        val high = CodexReasoningOptionDescriptor(CodexReasoningOptionId("high"), "High")
        assertTrue(model.matches("model-a-alias"))
        assertEquals(low.id, CodexReasoningOptionCatalog(model, listOf(low, high), low.id).defaultOptionId)
        assertFailsWith<IllegalArgumentException> { CodexModelId(" ") }
        assertFailsWith<IllegalArgumentException> { CodexModelIdentity(CodexModelId("model-a"), setOf(" ")) }
        assertFailsWith<IllegalArgumentException> { CodexReasoningOptionId(" ") }
        assertFailsWith<IllegalArgumentException> { CodexReasoningOptionCatalog(model, listOf(low, low)) }
        assertFailsWith<IllegalArgumentException> {
            CodexReasoningOptionCatalog(model, listOf(low), high.id)
        }
    }
}

private fun reasoningCatalog(
    canonical: String = "model-a-canonical",
    alias: String = "model-a-alias",
    options: List<String> = listOf("low", "high"),
) = buildJsonObject {
    put("found", true)
    put("canonicalModel", canonical)
    put("aliases", buildJsonArray {
        add(JsonPrimitive(canonical))
        add(JsonPrimitive(alias))
    })
    put("defaultOptionId", options.first())
    put("options", buildJsonArray {
        options.forEach { value ->
            add(buildJsonObject {
                put("id", value)
                put("displayName", value.replaceFirstChar(Char::uppercase))
                put("description", "Provider option $value")
            })
        }
    })
}

private fun completedEvents(text: String) = listOf(
    notification("item/completed", buildJsonObject {
        put("item", buildJsonObject {
            put("id", "message-1")
            put("type", "agentMessage")
            put("text", text)
            put("phase", "final_answer")
        })
    }),
    notification("turn/completed", buildJsonObject {
        put("turn", buildJsonObject { put("status", "completed") })
    }),
)
