package com.difft.android.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether an app-lock decision is scheduled but not applied yet. Set when a lock check is scheduled
 * and cleared only once the decision is applied (lock started, not needed, or the bounded retries
 * gave up), so the 150 ms retries that run outside the check job keep it set. Reading it consumes
 * nothing, unlike `ScreenLockUtil.recentlyUnlocked`.
 *
 * A quick check decides in about 100 ms; the full (deeplink) check takes about 1.1 s and the flag
 * may drop and rise again between its two passes. A flag still set after [MAX_PENDING_MS] means a
 * path missed its clear, so a watchdog clears it rather than keep every startup prompt waiting.
 */
class ScreenLockPending(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _pending = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _pending.asStateFlow()

    @Volatile
    private var setAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!_pending.value) return
            val age = elapsed() - setAt
            if (age < MAX_PENDING_MS) {
                handler.postDelayed(this, MAX_PENDING_MS - age)
                return
            }
            L.w { "[ScreenLock] pending flag expired ageMs=$age" }
            _pending.value = false
        }
    }

    fun onCheckScheduled() {
        setAt = elapsed()
        _pending.value = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, MAX_PENDING_MS)
    }

    /** A retry was scheduled: the decision is still open. */
    fun onRetryScheduled() = Unit

    fun onDecided() {
        handler.removeCallbacks(watchdog)
        _pending.value = false
    }

    companion object {
        const val MAX_PENDING_MS = 5_000L
    }
}
