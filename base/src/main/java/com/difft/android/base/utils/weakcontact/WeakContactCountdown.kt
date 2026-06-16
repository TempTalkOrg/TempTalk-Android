package com.difft.android.base.utils.weakcontact

import android.os.SystemClock
import kotlin.math.ceil

/**
 * Pure-function helper for the weak-contact countdown.
 *
 * `daysLeft = ceil((expireAt − now) / oneDay)`, floored at 1 (never shows "0 days"). `now` is
 * rebuilt from the server-time anchor plus the device monotonic clock, so it is immune to local
 * clock tampering and consistent across devices.
 *
 * The countdown is display-only; removal is driven by the server (changeType=1 / reconcile vanish branch).
 */
object WeakContactCountdown {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Stateless arithmetic (easy to unit-test).
     *
     * @param serverNow server clock at anchor time (ms UTC), paired with [anchorElapsed]
     * @param anchorElapsed SystemClock.elapsedRealtime() captured at [serverNow]
     * @param nowElapsed current SystemClock.elapsedRealtime()
     * @param expireAt absolute expiry, ms UTC
     * @return days remaining, floored at 1 (display-only, does not trigger removal)
     */
    fun daysLeft(serverNow: Long, anchorElapsed: Long, nowElapsed: Long, expireAt: Long): Int {
        // Monotonic extrapolation: server time at anchor plus the real elapsed time since the anchor.
        val effectiveNow = serverNow + (nowElapsed - anchorElapsed)
        return daysFromRemaining(expireAt - effectiveNow)
    }

    /**
     * UI-bind entry: reads the current anchor from [WeakContactClock] and the current elapsedRealtime.
     *
     * Null anchor (e.g. just after process restart) falls back to `System.currentTimeMillis()`.
     * Never treat raw `elapsedRealtime` as Unix time — it is uptime-relative and not comparable to
     * [expireAt]'s UTC timestamp; with a null anchor only the wall clock is usable (±1 day error).
     */
    fun daysLeftFromClock(expireAt: Long): Int {
        val anchor = WeakContactClock.snapshot()
        return if (anchor == null) {
            daysFromRemaining(expireAt - System.currentTimeMillis())
        } else {
            daysLeft(anchor.serverNow, anchor.anchorElapsed, SystemClock.elapsedRealtime(), expireAt)
        }
    }

    /** Remaining ms → days (ceil, floor 1). Shows 1 right up to expiry; removal is server-driven. */
    private fun daysFromRemaining(remaining: Long): Int {
        if (remaining <= 0L) return 1
        return ceil(remaining.toDouble() / DAY_MS).toInt().coerceAtLeast(1)
    }
}
