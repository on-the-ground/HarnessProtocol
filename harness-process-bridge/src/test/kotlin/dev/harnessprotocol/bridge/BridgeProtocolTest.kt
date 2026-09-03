package dev.harnessprotocol.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BridgeProtocolTest {
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
}
