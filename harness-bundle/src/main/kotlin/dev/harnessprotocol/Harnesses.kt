package dev.harnessprotocol

import dev.harnessprotocol.codex.CodexHarness
import dev.harnessprotocol.codex.CodexSdkOptions
import dev.harnessprotocol.gemini.GeminiCliHarness
import dev.harnessprotocol.gemini.GeminiCliSdkOptions

/** Convenience entry point for consumers that want one dependency and only the common port. */
object Harnesses {
    /** Opens a provider using deployment-level defaults while returning only the common port. */
    @JvmStatic
    fun create(provider: ProviderId): AgentHarness = when (provider.value) {
        "codex" -> codex()
        "gemini-cli" -> geminiCli()
        else -> throw IllegalArgumentException("Unknown agent harness provider: ${provider.value}")
    }

    @JvmStatic
    fun create(provider: String): AgentHarness = create(ProviderId(provider))

    @JvmStatic
    @JvmOverloads
    fun codex(options: CodexSdkOptions = CodexSdkOptions()): AgentHarness =
        CodexHarness.launch(options)

    @JvmStatic
    @JvmOverloads
    fun geminiCli(options: GeminiCliSdkOptions = GeminiCliSdkOptions()): AgentHarness =
        GeminiCliHarness.launch(options)
}
