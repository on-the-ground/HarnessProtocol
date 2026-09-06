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
    /** Specific Codex executable for the Python SDK; `null` uses its bundled runtime. */
    val codexExecutable: Path? = null,
    val environmentMode: EnvironmentMode = EnvironmentMode.INHERIT,
    /**
     * Codex-native reasoning effort applied to every task started by this harness.
     *
     * This is deliberately an adapter construction option rather than a common
     * [dev.harnessprotocol.TaskRequest] field: the value and its effect are owned by Codex.
     * `null` preserves the thread/provider default.
     */
    val reasoningEffort: String? = null,
) {
    init {
        require(reasoningEffort == null || reasoningEffort.isNotBlank()) {
            "reasoningEffort must be null or non-blank"
        }
    }

    enum class EnvironmentMode { INHERIT, REPLACE }
}

internal const val CODEX_EXECUTABLE_ENV = "HARNESS_CODEX_EXECUTABLE"

internal fun CodexSdkOptions.hostEnvironment(): Map<String, String> = buildMap {
    putAll(environment)
    remove(CODEX_EXECUTABLE_ENV)
    codexExecutable?.let { put(CODEX_EXECUTABLE_ENV, it.toAbsolutePath().normalize().toString()) }
}
