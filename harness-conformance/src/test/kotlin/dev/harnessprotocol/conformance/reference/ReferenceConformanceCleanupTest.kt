package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.conformance.HarnessConformanceCleanupTest
import dev.harnessprotocol.conformance.HarnessFixture

class ReferenceConformanceCleanupTest : HarnessConformanceCleanupTest() {
    override fun fixture(): HarnessFixture = ReferenceFixture()
}
