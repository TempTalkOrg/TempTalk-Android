package com.difft.android.base.utils.weakcontact

import com.difft.android.base.utils.time.ServerTimeProvider
import kotlin.math.ceil










object WeakContactCountdown {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    








    fun daysLeft(serverNow: Long, anchorElapsed: Long, nowElapsed: Long, expireAt: Long): Int {
        
        val effectiveNow = serverNow + (nowElapsed - anchorElapsed)
        return daysFromRemaining(expireAt - effectiveNow)
    }

    
    fun daysLeftFromClock(expireAt: Long): Int =
        daysFromRemaining(expireAt - ServerTimeProvider.nowMillis())

    
    private fun daysFromRemaining(remaining: Long): Int {
        if (remaining <= 0L) return 1
        return ceil(remaining.toDouble() / DAY_MS).toInt().coerceAtLeast(1)
    }
}
