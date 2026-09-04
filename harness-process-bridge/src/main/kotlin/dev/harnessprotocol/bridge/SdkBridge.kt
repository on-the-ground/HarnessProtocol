package dev.harnessprotocol.bridge

import dev.harnessprotocol.HarnessTransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal request/event boundary between Kotlin and a vendor SDK host process.
 *
 * Event delivery per execution is lossless and ordered: [events] is backed by
 * an unbounded mailbox that only the owning [ProcessTaskHarness] drains. The
 * flow completes normally when [release] is called and exceptionally when the
 * host process dies or the bridge is closed.
 */
interface SdkBridge : AutoCloseable {
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject

    fun events(executionId: String): Flow<JsonObject>

    /** Drops routing state for a terminal execution. Idempotent. */
    fun release(executionId: String)
}

/** Transport evidence used by the new task port; ordinary failures do not prove non-delivery. */
interface ConfirmedSdkBridge : SdkBridge {
    suspend fun requestConfirmed(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject
}
class BridgeNotDeliveredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class BridgeAcceptanceUnconfirmedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

suspend fun SdkBridge.confirmedRequest(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
    if (this is ConfirmedSdkBridge) requestConfirmed(method, params) else request(method, params)

class JsonLineProcessBridge(
    private val command: List<String>,
    private val workingDirectory: Path? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val json: Json = DefaultBridgeJson,
) : ConfirmedSdkBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val nextRequestId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val mailboxes = ConcurrentHashMap<String, Channel<JsonObject>>()
    private val released: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val stderr = StringBuilder()
    private val protocolErrors = StringBuilder()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var closed = false

    /** Set once the host died unexpectedly; the bridge does not restart it. */
    @Volatile
    private var dead: Throwable? = null

    /** Protocol violations observed from the host (unknown execution, late events). Diagnostic only. */
    val protocolErrorLog: String get() = synchronized(protocolErrors) { protocolErrors.toString() }

    override suspend fun request(method: String, params: JsonObject): JsonObject = try {
        requestConfirmed(method, params)
    } catch (failure: Exception) {
        throw HarnessTransportException(failure.message ?: "SDK bridge request failed", failure)
    }

    override suspend fun requestConfirmed(method: String, params: JsonObject): JsonObject {
        if (closed) throw BridgeNotDeliveredException("SDK bridge is closed")
        dead?.let { throw BridgeNotDeliveredException("SDK bridge host is no longer running; open a new harness", it) }
        try { ensureStarted() } catch (failure: Exception) {
            throw BridgeNotDeliveredException("Unable to start SDK bridge", failure)
        }
        val id = nextRequestId.incrementAndGet()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        dead?.let { result.completeExceptionally(it) }

        val message = buildJsonObject {
            put("kind", "request")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        var writeStarted = false
        try {
            writeMutex.withLock {
                val activeWriter = writer ?: throw BridgeNotDeliveredException("SDK bridge is closed")
                writeStarted = true
                activeWriter.write(json.encodeToString(JsonElement.serializer(), message))
                activeWriter.newLine()
                activeWriter.flush()
            }
            return result.await()
        } catch (failure: Throwable) {
            if (!writeStarted || failure is BridgeNotDeliveredException) throw BridgeNotDeliveredException("Request was not delivered", failure)
            throw BridgeAcceptanceUnconfirmedException("Request $id ($method) acceptance is unconfirmed", failure)
        } finally {
            pending.remove(id)
        }
    }

    override fun events(executionId: String): Flow<JsonObject> = mailbox(executionId).receiveAsFlow()

    override fun release(executionId: String) {
        released += executionId
        mailboxes.remove(executionId)?.close()
    }

    private fun mailbox(executionId: String): Channel<JsonObject> =
        mailboxes.computeIfAbsent(executionId) {
            Channel<JsonObject>(Channel.UNLIMITED).also { channel ->
                dead?.let { channel.close(it) }
                if (closed || executionId in released) channel.close()
            }
        }

    private suspend fun ensureStarted() {
        if (process?.isAlive == true) return

        startMutex.withLock {
            if (process?.isAlive == true) return
            require(command.isNotEmpty()) { "bridge command must not be empty" }

            val builder = ProcessBuilder(command)
            workingDirectory?.let { builder.directory(it.toFile()) }
            builder.environment().putAll(environment)
            val started = builder.start()
            process = started
            writer = started.outputWriter(StandardCharsets.UTF_8)

            scope.launch { readStdout(started) }
            scope.launch { readStderr(started) }
            scope.launch {
                val exitCode = started.waitFor()
                if (!closed) {
                    failEverything(HarnessTransportException("SDK bridge exited with code $exitCode${stderrSuffix()}"))
                }
            }
        }
    }

    private suspend fun readStdout(active: Process) {
        try {
            active.inputReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach(::routeLine)
            }
        } catch (failure: Throwable) {
            if (!closed) failEverything(HarnessTransportException("Failed to read SDK bridge output", failure))
        }
    }

    private fun readStderr(active: Process) {
        active.errorReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                synchronized(stderr) {
                    if (stderr.length < MAX_STDERR_CHARS) {
                        stderr.appendLine(line)
                    }
                }
            }
        }
    }

    private fun routeLine(line: String) {
        if (line.isBlank()) return
        val message = try {
            json.parseToJsonElement(line).jsonObject
        } catch (failure: Throwable) {
            failEverything(HarnessTransportException("SDK bridge emitted invalid JSON: $line", failure))
            return
        }

        when (message["kind"]?.jsonPrimitive?.content) {
            "response" -> routeResponse(message)
            "event" -> {
                val executionId = message.getValue("executionId").jsonPrimitive.content
                val payload = message.getValue("payload").jsonObject
                if (executionId in released) {
                    recordProtocolError("event after release for execution '$executionId'")
                } else if (mailbox(executionId).trySend(payload).isFailure) {
                    // Events may legitimately arrive before the adapter subscribes; the
                    // mailbox is created on demand and buffers them. UNLIMITED never fails
                    // while open, so a failure means release() raced this line.
                    recordProtocolError("event after release for execution '$executionId'")
                }
            }
        }
    }

    private fun routeResponse(message: JsonObject) {
        val id = message.getValue("id").jsonPrimitive.content.toLong()
        val waiter = pending.remove(id) ?: return
        val error = message["error"]
        if (error != null) {
            waiter.completeExceptionally(
                if (error.jsonObject["delivery"]?.jsonPrimitive?.content == "not_accepted")
                    BridgeNotDeliveredException(error.jsonObject["message"]?.jsonPrimitive?.content ?: error.toString())
                else BridgeAcceptanceUnconfirmedException(error.jsonObject["message"]?.jsonPrimitive?.content ?: error.toString()),
            )
        } else {
            waiter.complete(message["result"]?.jsonObject ?: JsonObject(emptyMap()))
        }
    }

    /** Host is gone: fail every pending request and every open execution mailbox. */
    private fun failEverything(failure: Throwable) {
        if (!closed && dead == null) dead = failure
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        val open = mailboxes.keys.toList()
        open.forEach { id -> mailboxes.remove(id)?.close(failure) }
    }

    private fun recordProtocolError(text: String) = synchronized(protocolErrors) {
        if (protocolErrors.length < MAX_STDERR_CHARS) protocolErrors.appendLine(text)
    }

    private fun stderrSuffix(): String = synchronized(stderr) {
        if (stderr.isEmpty()) "" else ":\n${stderr.toString().trim()}"
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeWriter = writer
        writer = null
        val active = process
        // App Server is a child of the Python host. Destroying only the host leaves
        // its owned runtime alive and keeps native session files locked on Windows.
        val descendants = active?.descendants()?.use { it.toList() }.orEmpty()
        // Let the host execute its finally/client.close path before force cleanup.
        runCatching { activeWriter?.close() }
        active?.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        descendants.asReversed().forEach { it.destroy() }
        active?.destroy()
        active?.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (active?.isAlive == true) active.destroyForcibly()
        runCatching {
            java.util.concurrent.CompletableFuture.allOf(*descendants.map { it.onExit() }.toTypedArray())
                .get(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        process = null
        failEverything(HarnessTransportException("SDK bridge closed"))
        scope.cancel()
    }

    private companion object {
        const val MAX_STDERR_CHARS = 32_768
    }
}

val DefaultBridgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
