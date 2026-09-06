package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

interface PersistenceFailureFixture : AcceptanceFixture {
    val supportsChangedInstructions: Boolean
    fun recreateHarness(): AgentHarness
    /** Make this fixture's actual persisted files unavailable after closing the first harness. */
    fun hideStoredContext()
    fun restoreStoredContext()
}
