package dev.harnessprotocol.gemini

import java.nio.file.Path

/** Configuration of the native SDK host process. */
data class GeminiCliSdkOptions(
    val bridgeScript: Path? = null,
    val sdkModule: String? = null,
    val nodeCommand: List<String> = listOf(
        System.getenv("HARNESS_GEMINI_NODE")?.takeIf(String::isNotBlank) ?: "node",
    ),
    val processWorkingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
)

