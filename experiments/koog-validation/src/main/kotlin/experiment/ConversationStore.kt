package experiment

import ai.koog.prompt.message.Message
import dev.harnessprotocol.HarnessTransportException
import dev.harnessprotocol.SessionId
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Adapter-owned durable completed-turn history, deliberately distinct from graph checkpoints.
 * Single process/writer experiment; cross-process locking and interrupted-turn recovery are not provided.
 */
class ConversationStore(private val directory: Path) {
    init { Files.createDirectories(directory) }
    private fun path(id: SessionId): Path {
        val valid = runCatching { UUID.fromString(id.value).toString() == id.value }.getOrDefault(false)
        if (!valid) throw HarnessTransportException("Unknown conversation identifier")
        return directory.resolve("${id.value}.json")
    }
    fun create(): SessionId = SessionId(UUID.randomUUID().toString()).also { save(it, emptyList()) }
    fun load(id: SessionId): List<Message> = try {
        Json.decodeFromString<List<Message>>(Files.readString(path(id)))
    } catch (e: Exception) { throw HarnessTransportException("Cannot load conversation ${id.value}", e) }
    fun save(id: SessionId, messages: List<Message>) {
        val target = path(id)
        val staging = directory.resolve("${id.value}.pending")
        try {
            Files.writeString(staging, Json.encodeToString(messages))
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) { throw HarnessTransportException("Cannot persist completed conversation", e) }
    }
}
