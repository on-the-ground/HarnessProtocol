package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

/** Real runtime admission; the model boundary can hold the work after acceptance. */
interface AcceptanceFixture : AutoCloseable {
    val harness: AgentHarness
    val spec: SessionSpec
    val observation: RuntimeObservation
}

/** Available only where a real request/acknowledgement boundary exists. */
interface StartAcceptanceFixture : AcceptanceFixture {
    val start: StartControl
    /** Correlation identities observed at the delivery boundary, not copied from the thrown exception. */
    val submittedStarts: List<UnconfirmedStart>
}

/** Real caller-mediated effect. Observations are taken from native responses and the effect resource. */
interface ResponseAcceptanceFixture : AcceptanceFixture {
    val response: ResponseControl
    fun effectCount(): Int
}

/** Hold a submitted response before native delivery, without replacing the native interaction. */
interface InteractionRaceFixture : ResponseAcceptanceFixture {
    fun holdResponse()
    suspend fun awaitResponseSubmission()
    fun releaseResponse()
}
