package dev.harnessprotocol

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.harnessprotocol.codex.CodexHarness
import dev.harnessprotocol.codex.CodexSdkOptions
import dev.harnessprotocol.gemini.GeminiCliHarness
import dev.harnessprotocol.gemini.GeminiCliSdkOptions
import dev.harnessprotocol.koog.KoogHarness

/** Adapter selection at the application's configuration boundary. */
object Harnesses {
    @JvmStatic fun create(provider: String): AgentHarness = when (provider) {
        "codex" -> codex()
        "gemini-cli" -> geminiCli()
        "koog" -> throw IllegalArgumentException("Koog requires a configured model executor; use Harnesses.koog")
        else -> throw IllegalArgumentException("Unknown harness provider: $provider")
    }
    @JvmStatic fun create(provider: ProviderId): AgentHarness = create(provider.value)
    fun codex(options: CodexSdkOptions = CodexSdkOptions(), storageNamespace: StorageNamespace? = null): AgentHarness =
        CodexHarness.launch(options, storageNamespace)
    fun geminiCli(options: GeminiCliSdkOptions = GeminiCliSdkOptions(), storageNamespace: StorageNamespace? = null): AgentHarness =
        GeminiCliHarness.launch(options, storageNamespace)
    fun koog(executorFactory: () -> PromptExecutor, model: LLModel, tools: ToolRegistry = ToolRegistry {}): AgentHarness =
        KoogHarness(executorFactory, model, tools)
}
