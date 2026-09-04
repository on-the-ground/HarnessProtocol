package experiment

import dev.harnessprotocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow

/** Bounded per-observer delivery; completion never waits for an observer. */
internal class ObservationStream(private val id: ExecutionId, private val capacity: Int = 32) {
    private class Reader {
        val queue = ArrayDeque<AgentEvent>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var dropped = 0L
    }
    private val readers = mutableSetOf<Reader>()
    private var terminal: AgentEvent? = null
    private val lock = Any()
    fun publish(event: AgentEvent, last: Boolean = false) = synchronized(lock) {
        if (terminal != null) return@synchronized
        if (last) terminal = event
        for (reader in readers) {
            if (reader.queue.size == capacity) { reader.queue.removeFirst(); reader.dropped++ }
            reader.queue.addLast(event)
            reader.wake.trySend(Unit)
        }
    }
    val flow = flow {
        val reader = Reader()
        synchronized(lock) {
            readers += reader
            terminal?.let { reader.queue.add(it); reader.wake.trySend(Unit) }
        }
        try {
            while (true) {
                reader.wake.receive()
                val (gap, batch, done) = synchronized(lock) {
                    val result = Triple(reader.dropped, reader.queue.toList(), terminal != null)
                    reader.queue.clear(); reader.dropped = 0
                    result
                }
                if (gap > 0) emit(AgentEvent.ObservationGap(id, gap))
                batch.forEach { emit(it) }
                if (done) break
            }
        } finally {
            synchronized(lock) { readers -= reader }
            reader.wake.close()
        }
    }
}
