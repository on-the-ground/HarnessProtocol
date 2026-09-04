package dev.harnessprotocol.integration

import dev.harnessprotocol.*
import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class NativeContextTest : HarnessContextConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun contextFixture(): ContextFixture = object : ContextFixture {
        override val observation = ModelBoundary()
        override val spec = factory.spec().copy(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
        private val delivery = NativeDeliveryBridge(nativeBridge(factory, observation, directory))
        override val start = delivery.startControl
        override val submittedStarts get() = delivery.startReferences.toList()
        override val harness = processHarness(factory, delivery, StorageNamespace(directory.toString()))
        override fun recreateHarness() = factory.create(observation, directory)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
class CodexNativeContextTest : NativeContextTest() { override val factory = CodexNativeFactory }
class GeminiNativeContextTest : NativeContextTest() { override val factory = GeminiNativeFactory }
