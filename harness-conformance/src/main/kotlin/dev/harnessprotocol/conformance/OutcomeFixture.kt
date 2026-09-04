package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

enum class OutputCase { PARTIAL, MISSING, EMPTY }

/** Model/runtime evidence controls. No method directly reports a Port outcome. */
interface OutcomeFixture : AutoCloseable {
    val harness: AgentHarness
    val spec: SessionSpec
    fun beginModel()
    fun finishModel()
    fun failModel()
    suspend fun loseObservation(session: AgentSession)
}
