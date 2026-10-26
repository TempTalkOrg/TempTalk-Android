package com.difft.android.call.state

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory map from a call room to the critical alerts published for it, plus the rooms whose
 * call is over (#1387).
 *
 * An alert's notification id is one per alert, and no other component stores its room, so the
 * publisher [record]s it here and a call end [end]s the room: the room is remembered as ended, so
 * every later admission of an alert for it is silenced ([CriticalAlertCallGate.shouldSilence]),
 * and the ids published so far are handed back to be dismissed. Entries expire after [TTL_MS] and
 * the ended set is bounded. The registry is not persisted: after process death no ringtone,
 * flashlight or page survives, only a silent tray notification can.
 *
 * All methods are synchronized on one lock and cheap, so they are safe on any thread.
 */
@Singleton
class CriticalAlertRoomRegistry internal constructor(
    private val nowMs: () -> Long,
) {

    @Inject
    constructor() : this(System::currentTimeMillis)

    private class Entry(val ids: MutableSet<Int>, var touchedAt: Long)

    private val lock = Any()

    // roomId -> alert ids not ended yet. Guarded by lock.
    private val recorded = LinkedHashMap<String, Entry>()

    // roomId -> time it ended. Guarded by lock.
    private val ended = LinkedHashMap<String, Long>()

    /** [notificationId] was published for [roomId]. */
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

    /**
     * Marks [roomId] as ended, even when nothing was recorded for it, and returns and forgets the
     * alert ids recorded for it. A second call returns an empty set.
     */
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
        /** How long an ended room keeps silencing late alerts, and how long a recorded alert is kept. */
        const val TTL_MS = 5 * 60_000L

        private const val MAX_ROOMS = 64
    }
}
