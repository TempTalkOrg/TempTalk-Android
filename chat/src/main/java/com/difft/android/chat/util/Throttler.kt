package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper

/**
 * A class that will throttle the number of runnables executed to be at most once every specified
 * interval.
 *
 * Useful for performing actions in response to rapid user input where you want to take action on
 * the initial input but prevent follow-up spam.
 *
 * This is different from [Debouncer] in that it will run the first runnable immediately
 * instead of waiting for input to die down.
 *
 * See http://rxmarbles.com/#throttle
 *
 * Declared `open` for symmetry with [Debouncer] in case future tests need to mock it.
 */
open class Throttler(
    /** Only one runnable will be executed via [publish] every [intervalMs] milliseconds. */
    private val intervalMs: Long,
) {

    private val handler: Handler = Handler(Looper.getMainLooper())

    open fun publish(runnable: Runnable) {
        if (handler.hasMessages(WHAT)) return

        runnable.run()
        handler.sendMessageDelayed(handler.obtainMessage(WHAT), intervalMs)
    }

    open fun clear() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        private const val WHAT = 8675309
    }
}
