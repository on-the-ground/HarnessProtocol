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
 * an unbounded mailbox that only the owning [BridgeAgentExecution] drains. The
 * flow completes normally when [release] is called and exceptionally when the
 * host process dies or the bridge is closed.
 */
interface SdkBridge : AutoCloseable {
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject

    fun events(executionId: String): Flow<JsonObject>

    /** Drops routing state for a terminal execution. Idempotent. */
    fun release(executionId: String)
}

class JsonLineProcessBridge(
    private val command: List<String>,
    private val workingDirectory: Path? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val json: Json = DefaultBridgeJson,
) : SdkBridge {
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

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        if (closed) throw HarnessTransportException("SDK bridge is closed")
        dead?.let { throw HarnessTransportException("SDK bridge host is no longer running; open a new harness", it) }
        ensureStarted()
        val id = nextRequestId.incrementAndGet()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result

        val message = buildJsonObject {
            put("kind", "request")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        try {
            writeMutex.withLock {
                val activeWriter = writer ?: throw HarnessTransportException("SDK bridge is closed")
                activeWriter.write(json.encodeToString(JsonElement.serializer(), message))
                activeWriter.newLine()
                activeWriter.flush()
            }
        } catch (failure: Throwable) {
            pending.remove(id)
            result.completeExceptionally(failure)
        }

        return result.await()
    }

    override fun events(executionId: String): Flow<JsonObject> = mailbox(executionId).receiveAsFlow()

    override fun release(executionId: String) {
        released += executionId
        mailboxes.remove(executionId)?.close()
    }

    private fun mailbox(executionId: String): Channel<JsonObject> =
        mailboxes.computeIfAbsent(executionId) { Channel(Channel.UNLIMITED) }

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
                HarnessTransportException(error.jsonObject["message"]?.jsonPrimitive?.content ?: error.toString()),
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
        closed = true
        writer = null
        process?.destroy()
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
