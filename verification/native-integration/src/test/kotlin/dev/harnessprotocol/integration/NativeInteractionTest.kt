package dev.harnessprotocol.integration

import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CodexNativeInteractionTest : HarnessInteractionConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun interactionFixture(): InteractionRaceFixture = NativeResponseFixture(directory)
}
