package dev.harnessprotocol.bridge

import dev.harnessprotocol.legacy.AgentEvent
import dev.harnessprotocol.legacy.AgentExecutionException
import dev.harnessprotocol.legacy.AgentExecutionFailedException
import dev.harnessprotocol.legacy.FailureKind
import dev.harnessprotocol.legacy.AgentResult
import dev.harnessprotocol.legacy.ExecutionId
import dev.harnessprotocol.legacy.ExecutionState
import dev.harnessprotocol.legacy.HarnessTransportException
import dev.harnessprotocol.legacy.SessionId
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
import kotlin.test.assertFalse
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
                    print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "started"}}), flush=True)
                    for i in range($events):
                        print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "delta", "text": str(i)}}), flush=True)
                    ending = "$ending"
                    if ending == "exit":
                        sys.exit(3)
                    if ending == "garbage":
                        print("this is not json", flush=True)
                        sys.exit(0)
                    if ending == "complete":
                        print(json.dumps({"kind": "event", "executionId": "e1", "payload": {"type": "done"}}), flush=True)
                        continue
                    if ending == "hang":
                        continue
                else:
                    print(json.dumps({"kind": "response", "id": req["id"], "result": {}}), flush=True)
            """.trimIndent(),
        )
        return listOf(py, script.toAbsolutePath().toString())
    }

    private fun map(id: ExecutionId): (JsonObject) -> List<AgentEvent> = { raw ->
        when ((raw["type"] as? JsonPrimitive)?.contentOrNull) {
            "started" -> listOf(AgentEvent.ExecutionStarted(id))
            "delta" -> listOf(AgentEvent.MessageDelta(id, (raw["text"] as JsonPrimitive).content))
            "done" -> listOf(AgentEvent.ExecutionCompleted(id, AgentResult("ok")))
            else -> emptyList()
        }
    }

    private fun start(bridge: SdkBridge, scope: CoroutineScope): BridgeAgentExecution = runBlocking {
        val id = ExecutionId((bridge.request("start_execution", buildJsonObject { put("sessionId", "s") })["executionId"] as JsonPrimitive).content)
        BridgeAgentExecution(id, SessionId("s"), bridge.events(id.value), bridge, scope, map(id))
    }

    private fun environmentHost(): List<String>? {
        val py = python() ?: return null
        val script = Files.createTempFile("environment-host-", ".py")
        script.toFile().deleteOnExit()
        script.toFile().writeText(
            """
            import json, os, sys
            for line in sys.stdin:
                req = json.loads(line)
                result = {
                    "hasPath": "PATH" in os.environ,
                    "sentinel": os.environ.get("AHP_ENV_SENTINEL", ""),
                }
                print(json.dumps({"kind": "response", "id": req["id"], "result": result}), flush=True)
            """.trimIndent(),
        )
        return listOf(py, script.toAbsolutePath().toString())
    }

    private fun withBridge(ending: String, block: suspend (JsonLineProcessBridge, CoroutineScope) -> Unit) {
        val command = host(ending) ?: return println("skipped: no python interpreter")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = JsonLineProcessBridge(command)
        try {
            runBlocking { block(bridge, scope) }
        } finally {
            bridge.close()
            scope.cancel()
        }
    }

    @Test
    fun `completes through a real host process`() = withBridge("complete") { bridge, scope ->
        val execution = start(bridge, scope)
        assertEquals("ok", withTimeout(10_000) { execution.awaitResult() }.finalMessage)
    }

    @Test
    fun `inherit mode overlays entries and retains the parent environment`() {
        val command = environmentHost() ?: return println("skipped: no python interpreter")
        JsonLineProcessBridge(
            command = command,
            environment = mapOf("AHP_ENV_SENTINEL" to "inherited"),
        ).use { bridge ->
            val result = runBlocking { bridge.request("environment") }
            assertTrue((result["hasPath"] as JsonPrimitive).content.toBoolean())
            assertEquals("inherited", (result["sentinel"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `replace mode exposes only explicitly supplied environment entries`() {
        val command = environmentHost() ?: return println("skipped: no python interpreter")
        JsonLineProcessBridge(
            command = command,
            environment = mapOf("AHP_ENV_SENTINEL" to "isolated"),
            environmentMode = ProcessEnvironmentMode.REPLACE,
        ).use { bridge ->
            val result = runBlocking { bridge.request("environment") }
            assertFalse((result["hasPath"] as JsonPrimitive).content.toBoolean())
            assertEquals("isolated", (result["sentinel"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `host exit fails every active execution as transport`() = withBridge("exit") { bridge, scope ->
        val execution = start(bridge, scope)
        val failure = assertFailsWith<AgentExecutionFailedException> { withTimeout(10_000) { execution.awaitResult() } }
        assertEquals(ExecutionState.FAILED, execution.state.value)
        assertEquals(FailureKind.TRANSPORT, failure.kind)
        assertFailsWith<HarnessTransportException> { bridge.request("anything") }
    }

    @Test
    fun `host EOF without a terminal fails the execution`() = withBridge("garbage") { bridge, scope ->
        val execution = start(bridge, scope)
        assertFailsWith<AgentExecutionException> { withTimeout(10_000) { execution.awaitResult() } }
        assertEquals(ExecutionState.FAILED, execution.state.value)
    }

    @Test
    fun `explicit close settles as cancelled when the owner settles first`() = withBridge("hang") { bridge, scope ->
        val execution = start(bridge, scope)
        withTimeout(10_000) { execution.state.first { it == ExecutionState.RUNNING } }
        execution.settleCancelled()   // what BridgeHarnessRuntime.closeAll does before bridge.close()
        bridge.close()
        assertFailsWith<AgentExecutionException> { execution.awaitResult() }
        assertEquals(ExecutionState.CANCELLED, execution.state.value)
    }

    @Test
    fun `close without owner settlement fails as transport`() = withBridge("hang") { bridge, scope ->
        val execution = start(bridge, scope)
        withTimeout(10_000) { execution.state.first { it == ExecutionState.RUNNING } }
        bridge.close()
        assertFailsWith<AgentExecutionException> { withTimeout(10_000) { execution.awaitResult() } }
        assertEquals(ExecutionState.FAILED, execution.state.value)
    }
}
