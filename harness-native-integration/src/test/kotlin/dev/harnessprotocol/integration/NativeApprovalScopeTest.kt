package dev.harnessprotocol.integration

import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CodexNativeApprovalScopeTest : HarnessApprovalScopeConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun approvalFixture(): RepeatedApprovalFixture = NativeResponseFixture(directory)
}
