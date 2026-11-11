package com.difft.android.chat.ui

import android.os.SystemClock










internal class JumpTargetAnchor(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val holdMs: Long = HOLD_MS,
) {
    private var target = NO_TARGET
    private var since = 0L

    fun set(position: Int) {
        target = position
        since = now()
    }

    fun clear() {
        target = NO_TARGET
    }

    





    fun reanchorOnLayout(delta: Int): Int? {
        if (target == NO_TARGET || delta == 0) return null
        if (now() - since > holdMs) {
            clear()
            return null
        }
        return target
    }

    private companion object {
        const val NO_TARGET = -1
        const val HOLD_MS = 1_000L
    }
}
