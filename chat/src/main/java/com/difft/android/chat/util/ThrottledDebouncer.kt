package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.MainThread

/**
 * Mixes the behavior of [Throttler] and [Debouncer].
 *
 * Like a throttler, it will limit the number of runnables to be executed to be at most once every
 * specified interval, while allowing the first runnable to be run immediately.
 *
 * However, like a debouncer, instead of completely discarding runnables that are published in the
 * throttling period, the most recent one will be saved and run at the end of the throttling period.
 *
 * Useful for publishing a set of identical or near-identical tasks that you want to be responsive
 * and guaranteed, but limited in execution frequency.
 *
 * Declared `open` for symmetry with [Debouncer] in case future tests need to mock it.
 */
open class ThrottledDebouncer @MainThread constructor(
    /** Only one runnable will be executed via [publish] every [intervalMs] milliseconds. */
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
