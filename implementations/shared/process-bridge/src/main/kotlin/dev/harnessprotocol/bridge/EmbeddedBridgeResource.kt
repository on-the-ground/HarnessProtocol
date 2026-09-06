package dev.harnessprotocol.bridge

import dev.harnessprotocol.HarnessTransportException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Extracts an SDK host bundled in an adapter JAR to an executable filesystem path. */
object EmbeddedBridgeResource {
    fun extract(owner: Class<*>, resourceName: String, suffix: String): Path {
        val input = owner.getResourceAsStream(resourceName)
            ?: throw HarnessTransportException("Embedded SDK bridge is missing: $resourceName")
        val target = Files.createTempFile("harness-protocol-", suffix)
        input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        target.toFile().deleteOnExit()
        return target
    }
}
