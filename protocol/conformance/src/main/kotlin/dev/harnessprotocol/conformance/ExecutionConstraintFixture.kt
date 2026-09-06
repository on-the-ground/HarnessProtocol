package dev.harnessprotocol.conformance

import dev.harnessprotocol.TaskRequest

enum class ExecutionCase { READ_ONLY, WORKSPACE_DENIED_NETWORK, WORKSPACE_ALLOWED_NETWORK }
enum class ExecutionAttempt { WRITE_WORKSPACE, WRITE_ADDITIONAL, WRITE_OUTSIDE, USE_NETWORK }

/** Observations come from isolated actual files and a loopback HTTP endpoint. */
interface ExecutionConstraintFixture : AcceptanceFixture {
    fun prepare(attempt: ExecutionAttempt): TaskRequest
    fun writtenTargets(): Set<String>
    fun networkRequests(): Int
}
