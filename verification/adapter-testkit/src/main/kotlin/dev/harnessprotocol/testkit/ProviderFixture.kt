package dev.harnessprotocol.testkit

import kotlinx.serialization.json.JsonObject

/**
 * Provider-specific raw events an adapter test supplies so the shared contract
 * can drive one execution through every lifecycle outcome.
 *
 * Each function returns the raw host envelope(s) the adapter's mapper expects.
 */
interface ProviderFixture {
    /** Events that put the execution into RUNNING. */
    fun started(): List<JsonObject>

    /** Events carrying agent text progress. */
    fun messageDelta(text: String): List<JsonObject>

    /** Events that end the execution successfully with [finalText] as the final message. */
    fun completed(finalText: String): List<JsonObject>

    /** Events that end the execution as a provider failure. */
    fun failed(message: String): List<JsonObject>

    /** Events that end the execution as cancelled. */
    fun cancelled(): List<JsonObject>
}
