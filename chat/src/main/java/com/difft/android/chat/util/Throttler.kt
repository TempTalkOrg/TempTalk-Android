package com.difft.android.chat.util

import android.os.Handler
import android.os.Looper







open class Throttler(
    
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
