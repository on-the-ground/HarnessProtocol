package dev.harnessprotocol.integration

import dev.harnessprotocol.*
import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

abstract class NativePersistenceFailureTest : HarnessPersistenceFailureConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun persistenceFixture(): PersistenceFailureFixture = object : PersistenceFailureFixture {
        override val supportsChangedInstructions = factory != CodexNativeFactory
        override val observation = ModelBoundary()
        override val spec = factory.spec().copy(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
        override val harness = factory.create(observation, directory)
        private val hidden = mutableListOf<Pair<Path, Path>>()
        override fun recreateHarness() = factory.create(observation, directory)
        override fun hideStoredContext() {
            val root = directory.toRealPath()
            val storage = root.resolve(if (factory == CodexNativeFactory) "codex-home/sessions" else "gemini-home/.gemini/tmp")
            val files = Files.walk(storage).use { paths -> paths.filter {
                Files.isRegularFile(it) && if (factory == CodexNativeFactory) it.toString().endsWith(".jsonl")
                else it.parent.fileName.toString() == "chats" && (it.toString().endsWith(".json") || it.toString().endsWith(".jsonl"))
            }.toList() }
            check(files.isNotEmpty()) { "No native persisted history found in the isolated fixture" }
            files.forEach { file ->
                check(file.toRealPath().startsWith(root))
                val target = file.resolveSibling(file.fileName.toString() + ".ahp-hidden")
                check(target.normalize().startsWith(root))
                Files.move(file, target)
                hidden += file to target
            }
        }
        override fun restoreStoredContext() { hidden.forEach { (file, target) -> if (Files.exists(target)) Files.move(target, file) }; hidden.clear() }
        override fun close() { try { harness.close(); restoreStoredContext() } finally { observation.close() } }
    }
}
class CodexNativePersistenceFailureTest : NativePersistenceFailureTest() { override val factory = CodexNativeFactory }
class GeminiNativePersistenceFailureTest : NativePersistenceFailureTest() { override val factory = GeminiNativeFactory }
