package dev.harnessprotocol.testkit

import dev.harnessprotocol.AgentSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Declares, independently of the adapter's own conversion code, what the bridge
 * envelope must contain for a given [AgentSpec].
 *
 * Implementations are a list of rules; each rule inspects the spec and asserts
 * on the JSON. Keep them declarative so the test never re-implements the adapter.
 */
fun interface IntentProjection {
    fun assertPreserved(spec: AgentSpec, sent: JsonObject)
}

/** Assertion helpers shared by projections. */
object Envelope {
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.assertAbsent(key: String, because: String) {
        if (containsKey(key)) fail("expected '$key' to be absent ($because), got ${this[key]}")
    }

    fun JsonObject.assertString(key: String, expected: String) {
        assertEquals(expected, string(key), "envelope['$key']")
    }

    fun JsonObject.assertStrings(key: String, expected: List<String>) {
        val actual = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }
        assertEquals(expected, actual, "envelope['$key']")
    }

    fun JsonObject.assertNullableString(key: String, expected: String?, absentWhenNull: Boolean = true) {
        if (expected == null && absentWhenNull) assertAbsent(key, "null means provider default") else assertString(key, expected!!)
    }

    fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.map { it as JsonObject } ?: emptyList()

    fun assertNoValue(element: JsonElement?, because: String) = assertNull(element, because)
}
