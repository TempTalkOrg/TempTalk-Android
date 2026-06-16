package com.difft.android.base.utils.weakcontact

/**
 * Process-level in-memory singleton holding the server-time anchor.
 *
 * The weak-contact countdown ([WeakContactCountdown]) rebuilds "now" from a server clock plus the
 * device monotonic clock, so it is immune to local clock tampering and consistent across devices.
 * This singleton only holds the anchor; it does no arithmetic.
 *
 * Written by reconcile and by notify (changeType=0/1) once a serverTimestamp is available.
 * Read by [WeakContactCountdown.daysLeftFromClock].
 *
 * Not persisted: the anchor is a transient snapshot of the server clock at fetch/notify time and is
 * lost on process restart (the countdown then falls back to the local wall clock until the next
 * reconcile re-anchors).
 */
object WeakContactClock {

    /**
     * @param serverNow server clock at anchor time (ms UTC)
     * @param anchorElapsed SystemClock.elapsedRealtime() captured at the same instant as serverNow
     */
    data class Anchor(val serverNow: Long, val anchorElapsed: Long)

    @Volatile
    private var anchor: Anchor? = null

    /**
     * @param serverNow server clock (ms UTC). `serverNow <= 0` is invalid and does NOT overwrite a
     *   good anchor (guards against a dirty anchor clobbering a valid one).
     * @param elapsedRealtime SystemClock.elapsedRealtime() captured at the same instant as serverNow
     */
    fun update(serverNow: Long, elapsedRealtime: Long) {
        if (serverNow <= 0L) return
        anchor = Anchor(serverNow, elapsedRealtime)
    }

    /** Current anchor, or null before the first reconcile/notify (e.g. just after process restart). */
    fun snapshot(): Anchor? = anchor
}
