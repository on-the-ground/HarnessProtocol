package dev.harnessprotocol.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BridgeProtocolTest {
    private fun environmentHost(): List<String>? {
        val python = listOf("python3", "python").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return null
        val script = """
            import json, os, sys
            for line in sys.stdin:
                request = json.loads(line)
                result = {"hasPath": "PATH" in os.environ, "sentinel": os.environ.get("AHP_ENV_SENTINEL")}
                print(json.dumps({"kind": "response", "id": request["id"], "result": result}), flush=True)
        """.trimIndent()
        return listOf(python, "-c", script)
    }

    @Test
    fun `default JSON preserves arbitrary provider payload`() {
        val payload = buildJsonObject {
            put("type", "future_vendor_event")
            put("value", buildJsonObject { put("answer", 42) })
        }

        val decoded = DefaultBridgeJson.parseToJsonElement(
            DefaultBridgeJson.encodeToString(payload),
        )

        assertEquals("future_vendor_event", decoded.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `inherit mode overlays entries and retains the parent environment`() {
        val command = environmentHost() ?: return
        JsonLineProcessBridge(
            command = command,
            environment = mapOf("AHP_ENV_SENTINEL" to "inherited"),
        ).use { bridge ->
            val result = runBlocking { bridge.request("environment") }
            assertTrue(result["hasPath"]?.jsonPrimitive?.content.toBoolean())
            assertEquals("inherited", result["sentinel"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `replace mode exposes only explicitly supplied environment entries`() {
        val command = environmentHost() ?: return
        JsonLineProcessBridge(
            command = command,
            environment = mapOf("AHP_ENV_SENTINEL" to "isolated"),
            environmentMode = ProcessEnvironmentMode.REPLACE,
        ).use { bridge ->
            val result = runBlocking { bridge.request("environment") }
            assertFalse(result["hasPath"]?.jsonPrimitive?.content.toBoolean())
            assertEquals("isolated", result["sentinel"]?.jsonPrimitive?.content)
        }
    }
}
