package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit







open class Debouncer(
    
    private val intervalMs: Long,
) {

    private val handler: Handler = Handler(Looper.getMainLooper())

    
    constructor(threshold: Long, timeUnit: TimeUnit) : this(timeUnit.toMillis(threshold))

    open fun publish(runnable: Runnable) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, intervalMs)
    }

    open fun clear() {
        handler.removeCallbacksAndMessages(null)
    }
}
