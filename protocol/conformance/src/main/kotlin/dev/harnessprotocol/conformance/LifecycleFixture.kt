package dev.harnessprotocol.conformance

import dev.harnessprotocol.*

/** Boundary binding for lifecycle checks; close releases the real harness and fixture resources. */
interface LifecycleFixture : AutoCloseable {
    val harness: AgentHarness
    fun control(task: AgentTask): TaskLifecycleControl
}

/** Minimal lifecycle evidence controls for SDK-boundary lifecycle assertions. */
interface TaskLifecycleControl {
    suspend fun reportRunning()
    suspend fun reportMessageDelta(messageKey: String, text: String, role: MessageKind? = null)

    /** null supplies no additional output; it must not erase previously captured output. */
    suspend fun reportCompletion(output: OutputObservation? = null, stopReason: StopReason = StopReason.FINISHED)

    /** No classified kind means the fixture must not invent structured failure evidence. */
    suspend fun reportFailure(message: String, kind: FailureKind? = null)
    suspend fun reportCancelledTermination()
}

/** Actual model/runtime observations, independent from the expected Port output. */
interface RuntimeObservation : AutoCloseable {
    val observedContexts: List<String>

    /** Decoded string values that crossed the actual model boundary; used for exact input preservation. */
    val observedTextValues: List<String>
    fun hold()
    fun release()
}
