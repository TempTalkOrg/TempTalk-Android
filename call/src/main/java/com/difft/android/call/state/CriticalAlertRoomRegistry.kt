package com.difft.android.call.state

import javax.inject.Inject
import javax.inject.Singleton














@Singleton
class CriticalAlertRoomRegistry internal constructor(
    private val nowMs: () -> Long,
) {

    @Inject
    constructor() : this(System::currentTimeMillis)

    private class Entry(val ids: MutableSet<Int>, var touchedAt: Long)

    private val lock = Any()

    
    private val recorded = LinkedHashMap<String, Entry>()

    
    private val ended = LinkedHashMap<String, Long>()

    
    fun record(roomId: String, notificationId: Int) {
        synchronized(lock) {
            val now = nowMs()
            pruneLocked(now)
            val entry = recorded.getOrPut(roomId) { Entry(linkedSetOf(), now) }
            entry.ids.add(notificationId)
            entry.touchedAt = now
            trimLocked(recorded)
        }
    }

    



    fun end(roomId: String): Set<Int> = synchronized(lock) {
        val now = nowMs()
        pruneLocked(now)
        ended.remove(roomId)
        ended[roomId] = now
        trimLocked(ended)
        recorded.remove(roomId)?.ids?.toSet().orEmpty()
    }

    fun isEnded(roomId: String): Boolean = synchronized(lock) {
        val at = ended[roomId] ?: return@synchronized false
        if (nowMs() - at >= TTL_MS) {
            ended.remove(roomId)
            false
        } else {
            true
        }
    }

    private fun pruneLocked(now: Long) {
        ended.values.removeAll { now - it >= TTL_MS }
        recorded.values.removeAll { now - it.touchedAt >= TTL_MS }
    }

    private fun trimLocked(map: LinkedHashMap<String, *>) {
        while (map.size > MAX_ROOMS) {
            map.remove(map.keys.first())
        }
    }

    companion object {
        
        const val TTL_MS = 5 * 60_000L

        private const val MAX_ROOMS = 64
    }
}
