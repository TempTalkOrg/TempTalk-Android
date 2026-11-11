package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.MainThread















open class ThrottledDebouncer @MainThread constructor(
    
    private val intervalMs: Long,
) {

    private val handler = OverflowHandler()

    @MainThread
    open fun publish(runnable: Runnable) {
        handler.runnable = runnable

        if (handler.hasMessages(WHAT)) return

        val sinceLastRun = System.currentTimeMillis() - handler.lastRun
        val delay = (intervalMs - sinceLastRun).coerceAtLeast(0)

        handler.sendMessageDelayed(handler.obtainMessage(WHAT), delay)
    }

    @MainThread
    open fun clear() {
        handler.removeCallbacksAndMessages(null)
    }

    private class OverflowHandler : Handler(Looper.getMainLooper()) {

        var runnable: Runnable? = null
        var lastRun: Long = 0L

        override fun handleMessage(msg: Message) {
            if (msg.what == WHAT) {
                val r = runnable ?: return
                lastRun = System.currentTimeMillis()
                runnable = null
                r.run()
            }
        }
    }

    private companion object {
        private const val WHAT = 24601
    }
}
