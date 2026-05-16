package net.activitywatch.tv.sync

import net.activitywatch.tv.data.Event
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

private const val MAX_BUFFER = 1000

data class SseEvent(val seq: Long, val bucket: String, val event: Event)

class SyncEventBuffer {
    private val seq = AtomicLong(0L)
    private val lock = Object()
    private val buffer = ArrayDeque<SseEvent>()
    private val listeners = CopyOnWriteArrayList<(SseEvent) -> Unit>()

    fun append(bucket: String, event: Event): SseEvent {
        val sseEvent = SseEvent(seq.incrementAndGet(), bucket, event)
        synchronized(lock) {
            buffer.addLast(sseEvent)
            if (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        for (l in listeners) try { l(sseEvent) } catch (_: Exception) {}
        return sseEvent
    }

    fun ack(upToSeq: Long) {
        synchronized(lock) {
            while (buffer.isNotEmpty() && buffer.first().seq <= upToSeq) buffer.removeFirst()
        }
    }

    fun eventsAfter(afterSeq: Long): List<SseEvent> = synchronized(lock) {
        buffer.filter { it.seq > afterSeq }
    }

    fun addListener(fn: (SseEvent) -> Unit) { listeners.add(fn) }
    fun removeListener(fn: (SseEvent) -> Unit) { listeners.remove(fn) }
}
