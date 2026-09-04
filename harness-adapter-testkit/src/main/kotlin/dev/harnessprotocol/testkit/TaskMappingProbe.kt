package dev.harnessprotocol.testkit

import dev.harnessprotocol.*
import dev.harnessprotocol.runtime.ManagedTask
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Records synchronous mapper output from the production task runtime, without a host process. */
class TaskMappingProbe(id: TaskId, mapper: (ManagedTask) -> (JsonObject) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val task = ManagedTask(id, SessionId("mapping"), scope, {}, { _, _ -> })
    private val seen = mutableListOf<TaskEvent>()
    private val ingest = mapper(task)
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { task.events.collect { seen += it } } }
    fun map(raw: JsonObject): List<TaskEvent> {
        val offset = seen.size
        ingest(raw)
        return seen.drop(offset)
    }
    override fun close() { scope.cancel() }
}
