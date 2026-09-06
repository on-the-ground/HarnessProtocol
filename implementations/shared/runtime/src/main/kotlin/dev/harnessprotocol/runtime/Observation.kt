package dev.harnessprotocol.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Adapter implementation utility; subscription never controls native execution. */
internal class Observation<T>(private val gap: (Long) -> T, private val capacity: Int = 256) {
    private val lock = Any()
    private val subscribers = mutableSetOf<Subscriber>()
    private var closed = false
    private var terminal: T? = null

    val flow: Flow<T> = flow {
        val subscriber = synchronized(lock) {
            Subscriber().also { if (closed) it.finish(terminal) else subscribers += it }
        }
        try {
            for (event in subscriber.channel) emit(event)
            synchronized(lock) { subscriber.tail() }.forEach { emit(it) }
        } finally { synchronized(lock) { subscribers -= subscriber } }
    }

    fun publish(event: T) = synchronized(lock) {
        if (!closed) subscribers.forEach { it.offer(event) }
    }

    fun finish(event: T? = null) = synchronized(lock) {
        if (!closed) {
            closed = true
            terminal = event
            subscribers.forEach { it.finish(event) }
            subscribers.clear()
        }
    }

    private inner class Subscriber {
        val channel = Channel<T>(capacity)
        private var dropped = 0L
        private var last: T? = null
        fun offer(event: T) {
            if (dropped > 0) {
                if (channel.trySend(gap(dropped)).isSuccess) dropped = 0
                else { dropped++; return }
            }
            if (channel.trySend(event).isFailure) dropped++
        }
        fun finish(event: T?) { last = event; channel.close() }
        fun tail(): List<T> = buildList {
            if (dropped > 0) add(gap(dropped))
            last?.let { add(it) }
        }
    }
}
