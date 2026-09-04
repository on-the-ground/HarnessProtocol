package dev.harnessprotocol.integration

import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class NativeCleanupBudgetTest : HarnessCleanupBudgetConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun cleanupFixture(): AcceptanceFixture = object : AcceptanceFixture {
        override val observation = ModelBoundary()
        override val spec = factory.spec()
        override val harness = factory.create(observation, directory)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
class CodexNativeCleanupBudgetTest : NativeCleanupBudgetTest() { override val factory = CodexNativeFactory }
class GeminiNativeCleanupBudgetTest : NativeCleanupBudgetTest() { override val factory = GeminiNativeFactory }
class KoogNativeCleanupBudgetTest : NativeCleanupBudgetTest() { override val factory = KoogNativeFactory }
