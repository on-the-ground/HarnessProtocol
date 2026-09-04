package dev.harnessprotocol.codex

import java.nio.file.Path

/** Configuration of the native SDK host process. */
data class CodexSdkOptions(
    val bridgeScript: Path? = null,
    val pythonCommand: List<String> = listOf(
        System.getenv("HARNESS_CODEX_PYTHON")?.takeIf(String::isNotBlank) ?: "python",
    ),
    val processWorkingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
)

