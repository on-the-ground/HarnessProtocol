package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

/** Persistent storage survives recreation of a configured harness in the same application process. */
interface ContextFixture : StartAcceptanceFixture {
    fun recreateHarness(): AgentHarness
}
