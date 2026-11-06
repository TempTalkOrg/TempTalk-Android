package com.difft.android.chat.ui

import android.os.SystemClock

/**
 * The adapter position the message list just jumped to (a searched message, the first unread),
 * kept for a moment so the target stays in place while the window settles. A hosted conversation
 * opened from a search page is laid out while that page's keyboard is still closing, and a call
 * strip can appear above the list right after the first render; when the list then grows or
 * shrinks, the jump has to be applied again or the target drifts (a shrink would otherwise keep
 * the bottom anchored and scroll the target out). A drag, a newer jump or a scroll to the bottom
 * ends it, and it expires on its own.
 */
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

    /**
     * The position to scroll to again after a layout change of [delta] (old bottom minus new
     * bottom), or null. Any height change while a recent jump is pending re-applies the jump:
     * growth (a closing keyboard) and shrink (a call strip above the list) alike. Without a
     * pending jump the list keeps its own bottom anchoring.
     */
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
