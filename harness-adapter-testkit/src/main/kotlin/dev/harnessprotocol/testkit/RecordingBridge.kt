package dev.harnessprotocol.testkit

import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.bridge.SdkBridge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-memory [SdkBridge] for adapter contract tests.
 *
 * Records every request, answers from a scripted table, and lets a test inject
 * provider events, end the stream, or fail it, so lifecycle rules can be
 * exercised without a host process.
 */
class RecordingBridge(
    private val sessionIds: Iterator<String> = generateSequence(1) { it + 1 }.map { "session-$it" }.iterator(),
    private val executionIds: Iterator<String> = generateSequence(1) { it + 1 }.map { "execution-$it" }.iterator(),
) : SdkBridge {
    data class Request(val method: String, val params: JsonObject)

    private val recorded = mutableListOf<Request>()
    private val channels = mutableMapOf<String, Channel<JsonObject>>()
    private val releasedIds = mutableListOf<String>()
    private val handlers = mutableMapOf<String, (JsonObject) -> JsonObject>()

    /** Ordered requests received so far. */
    val requests: List<Request> get() = recorded.toList()

    /** Method names in order, for quick assertions. */
    val methods: List<String> get() = recorded.map { it.method }

    /** Execution IDs released by the adapter, in order. */
    val released: List<String> get() = releasedIds.toList()

    /** Last execution ID handed out by `start_execution`. */
    var lastExecutionId: String? = null
        private set

    /** Last session ID handed out by `create_session`. */
    var lastSessionId: String? = null
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** Overrides the response for [method]; throwing inside simulates a failed request. */
    fun respondTo(method: String, handler: (JsonObject) -> JsonObject) {
        handlers[method] = handler
    }

    fun paramsOf(method: String): List<JsonObject> = recorded.filter { it.method == method }.map { it.params }

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        if (closed) throw HarnessTransportException("SDK bridge is closed")
        recorded += Request(method, params)
        handlers[method]?.let { return it(params) }
        return when (method) {
            "create_session" -> {
                val id = sessionIds.next()
                lastSessionId = id
                buildJsonObject { put("sessionId", id) }
            }
            "resume_session" -> buildJsonObject { put("sessionId", (params["sessionId"] as JsonPrimitive).content) }
            "start_execution" -> {
                val id = executionIds.next()
                lastExecutionId = id
                buildJsonObject { put("executionId", id) }
            }
            else -> JsonObject(emptyMap())
        }
    }

    override fun events(executionId: String): Flow<JsonObject> = channel(executionId).receiveAsFlow()

    override fun release(executionId: String) {
        releasedIds += executionId
        channels.remove(executionId)?.close()
    }

    /** Injects one provider event for [executionId]. */
    suspend fun emit(executionId: String, event: JsonObject) {
        channel(executionId).send(event)
    }

    /** Injects an event for the most recently started execution. */
    suspend fun emit(event: JsonObject) = emit(requireNotNull(lastExecutionId) { "no execution started" }, event)

    /** Ends the event stream without a terminal event, as a host EOF would. */
    fun endStream(executionId: String) {
        channel(executionId).close()
    }

    /** Fails the event stream, as a host read failure would. */
    fun failStream(executionId: String, cause: Throwable = HarnessTransportException("SDK bridge exited")) {
        channel(executionId).close(cause)
    }

    override fun close() {
        closed = true
        channels.values.forEach { it.close(HarnessTransportException("SDK bridge closed")) }
    }

    private fun channel(executionId: String) =
        channels.getOrPut(executionId) { Channel(Channel.UNLIMITED) }
}
