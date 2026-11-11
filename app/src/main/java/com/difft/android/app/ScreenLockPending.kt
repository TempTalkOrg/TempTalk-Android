package com.difft.android.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow













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

    
    fun onRetryScheduled() = Unit

    fun onDecided() {
        handler.removeCallbacks(watchdog)
        _pending.value = false
    }

    companion object {
        const val MAX_PENDING_MS = 5_000L
    }
}
