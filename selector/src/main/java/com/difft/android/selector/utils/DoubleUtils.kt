package com.difft.android.selector.utils

import android.os.SystemClock

object DoubleUtils {
    private const val TIME = 600L

    private var lastClickTime: Long = 0

    @JvmStatic
    fun isFastDoubleClick(): Boolean {
        val time = SystemClock.elapsedRealtime()
        if (time - lastClickTime < TIME) {
            return true
        }
        lastClickTime = time
        return false
    }
}
