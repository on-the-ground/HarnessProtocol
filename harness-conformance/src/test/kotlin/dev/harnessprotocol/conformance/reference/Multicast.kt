package dev.harnessprotocol.conformance.reference

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 참조 엔진 전용 다중 구독 통로. [dev.harnessprotocol.AgentTask.events]가 요구하는
 * per-collector bounded queue + overflow gap + "terminal은 항상 마지막에 유실 없이 전달" 규칙을
 * TaskEvent/DiagnosticEvent 양쪽에 재사용하기 위한 최소 구현이다. 실제 adapter의 구현 전략을
 * 규정하지 않는다.
 */
internal class Multicast<T>(
    private val capacity: Int,
    private val gapEvent: (dropped: Long) -> T,
) {
    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    @Volatile
    private var closed = false

    @Volatile
    private var lastTerminal: T? = null

    val flow: Flow<T> = flow {
        val subscriber = Subscriber()
        subscribers += subscriber
        if (closed) subscriber.close(lastTerminal)
        try {
            for (event in subscriber.channel) emit(event)
            subscriber.tail().forEach { emit(it) }
        } finally {
            subscribers -= subscriber
        }
    }

    /** 유실 가능한 일반 이벤트. */
    fun offer(event: T) {
        if (closed) return
        subscribers.forEach { it.offer(event) }
    }

    /** [event]를 모든 구독자에게 유실 없이 마지막으로 전달한 뒤 통로를 닫는다. */
    fun finish(event: T) {
        if (closed) return
        closed = true
        lastTerminal = event
        subscribers.forEach { it.close(event) }
    }

    /** 최종 이벤트 없이 통로를 닫는다 (진단 통로 등 terminal 개념이 없는 경우). */
    fun close() {
        if (closed) return
        closed = true
        subscribers.forEach { it.close(null) }
    }

    private inner class Subscriber {
        val channel = Channel<T>(capacity)
        private var dropped = 0L

        @Volatile
        private var hasFinal = false

        @Volatile
        private var finalEvent: T? = null

        fun offer(event: T) {
            if (hasFinal) return
            if (dropped > 0) {
                if (channel.trySend(gapEvent(dropped)).isSuccess) dropped = 0 else {
                    dropped++
                    return
                }
            }
            if (channel.trySend(event).isFailure) dropped++
        }

        fun close(event: T?) {
            hasFinal = true
            finalEvent = event
            channel.close()
        }

        fun tail(): List<T> = buildList {
            if (dropped > 0) add(gapEvent(dropped))
            finalEvent?.let { add(it) }
        }
    }
}
