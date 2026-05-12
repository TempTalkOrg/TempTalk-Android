package com.difft.android.call.connect

import kotlin.math.min

internal object ConnectionBackoff {
    private const val MAX_DELAY_MS = 30_000L

    /**
     * @param failureIndex cumulative failure count (1 = already failed once; delay before the 2nd attempt)
     */
    fun delayMsBeforeRetryAfterFailure(failureIndex: Int): Long {
        if (failureIndex <= 0) return 0L
        return when (failureIndex) {
            1 -> 0L
            2 -> 500L
            3 -> 1_000L
            4 -> 2_000L
            5 -> 5_000L
            else -> {
                val exp = failureIndex - 5
                val base = 5_000L * (1L shl exp.coerceAtMost(4))
                min(MAX_DELAY_MS, base)
            }
        }
    }
}
