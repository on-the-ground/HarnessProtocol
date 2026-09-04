package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

/** Measurements supplied by a controlled model, with independently declared session accounting. */
interface AccountingFixture : AcceptanceFixture {
    val measurements: List<AgentUsage>
    val sessionMeasurements: List<AgentUsage?>
    /** Roles the configured runtime can actually identify for the controlled text response. */
    val completedMessageRoles: Set<MessageRole>
}
