package dev.harnessprotocol.gemini

import dev.harnessprotocol.*
import dev.harnessprotocol.bridge.*
import kotlin.test.assertIs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives [JsonLineProcessBridge] with a scripted Python host so process death,
 * EOF, invalid output and explicit close are exercised end to end.
 */
class ProcessLifecycleTest {
    private fun python(): String? =
        listOf(System.getenv("HARNESS_CODEX_PYTHON"), "python3", "python").filterNotNull().firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        }

    /** A host that answers start_execution, emits N events, then behaves per [ending]. */
    private fun host(ending: String, events: Int = 3): List<String>? {
        val py = python() ?: return null
        val script = Files.createTempFile("fake-host-", ".py")
        script.toFile().deleteOnExit()
        script.toFile().writeText(
            """
            import json, sys
            for line in sys.stdin:
                req = json.loads(line)
                if req.get("method") == "start_execution":
                    print(json.dumps({"kind": "response", "id": req["id"], "result": {"executionId": "e1"}}), flush=True)
                    print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "execution_started"}}), flush=True)
                    for i in range($events):
                        print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "content", "value": str(i)}}), flush=True)
                    ending = "$ending"
                    if ending == "exit":
                        sys.exit(3)
                    if ending == "garbage":
                        print("this is not json", flush=True)
                        sys.exit(0)
                    if ending == "complete":
                        print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "execution_completed"}}), flush=True)
                        continue
                    if ending == "hang":
                        continue
                else:
                    print(json.dumps({"kind": "response", "id": req["id"], "result": {"sessionId": "s"}}), flush=True)
            """.trimIndent(),
        )
        return listOf(py, script.toAbsolutePath().toString())
    }

    private fun withBridge(ending: String, block: suspend (JsonLineProcessBridge, GeminiCliHarness) -> Unit) {
        val command = requireNotNull(host(ending)) { "Python is required for the process lifecycle regression suite" }
        val bridge = JsonLineProcessBridge(command)
        GeminiCliHarness.usingBridge(bridge).use { harness -> runBlocking { block(bridge, harness) } }
    }
    private suspend fun start(harness: AgentHarness): AgentTask =
        harness.createSession(SessionSpec()).startTask(TaskRequest(TaskInput.Text("go")))

    @Test
    fun `completes through a real host process`() = withBridge("complete") { bridge, harness ->
        val execution = start(harness)
        assertEquals("012", assertIs<TaskOutput.Text>(assertIs<TaskOutcome.Completed>(withTimeout(10_000) { execution.awaitOutcome() }).output).text)
    }

    @Test
    fun `host exit leaves task unresolved and prevents bridge restart`() = withBridge("exit") { bridge, harness ->
        val execution = start(harness)
        val outcome = assertIs<TaskOutcome.Unresolved>(withTimeout(10_000) { execution.awaitOutcome() })
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
        assertEquals(UnresolvedReason.OBSERVATION_LOST, outcome.reason)
        assertFailsWith<HarnessTransportException> { bridge.request("anything") }
    }

    @Test
    fun `invalid output and EOF leave task unresolved`() = withBridge("garbage") { bridge, harness ->
        val execution = start(harness)
        assertIs<TaskOutcome.Unresolved>(withTimeout(10_000) { execution.awaitOutcome() })
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
    }

    @Test
    fun `harness close cannot manufacture cancellation evidence`() = withBridge("hang") { bridge, harness ->
        val execution = start(harness)
        withTimeout(10_000) { execution.state.first { it == TaskState.RUNNING } }
        harness.close()
        bridge.close()
        assertIs<TaskOutcome.Unresolved>(execution.awaitOutcome())
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
    }

    @Test
    fun `bridge close without native termination leaves task unresolved`() = withBridge("hang") { bridge, harness ->
        val execution = start(harness)
        withTimeout(10_000) { execution.state.first { it == TaskState.RUNNING } }
        bridge.close()
        assertIs<TaskOutcome.Unresolved>(withTimeout(10_000) { execution.awaitOutcome() })
        assertEquals(TaskState.UNRESOLVED, execution.state.value)
    }
}
