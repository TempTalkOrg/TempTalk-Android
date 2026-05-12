package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * A class that will throttle the number of runnables executed to be at most once every specified
 * interval. However, it could be longer if events are published consistently.
 *
 * Useful for performing actions in response to rapid user input, such as inputting text, where you
 * don't necessarily want to perform an action after *every* input.
 *
 * See http://rxmarbles.com/#debounce
 *
 * NOTE: Declared `open` so MockK in jobmanager unit tests can subclass and stub.
 * Methods exposed to mocks are also `open`.
 */
open class Debouncer(
    /** Only one runnable will be executed via [publish] every [intervalMs] milliseconds. */
    private val intervalMs: Long,
) {

    private val handler: Handler = Handler(Looper.getMainLooper())

    /** Convenience constructor accepting the threshold in an arbitrary time unit. */
    constructor(threshold: Long, timeUnit: TimeUnit) : this(timeUnit.toMillis(threshold))

    open fun publish(runnable: Runnable) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, intervalMs)
    }

    open fun clear() {
        handler.removeCallbacksAndMessages(null)
    }
}
