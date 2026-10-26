package com.difft.android.base.widget

/**
 * The dots that were lit when the pattern was released: they fade out over [durationMs] on their own,
 * while the selection behind them is cleared at once (a single tap or a finished gesture should not
 * vanish abruptly). The path is not kept; it disappears on release.
 */
internal class PatternReleaseFade(private val durationMs: Long = DEFAULT_DURATION_MS) {

    /** Lit dots as `x, y, alpha`. */
    val dots = ArrayList<FloatArray>()

    private var startedAt = 0L
    private var active = false

    fun start(now: Long) {
        startedAt = now
        active = dots.isNotEmpty()
    }

    fun clear() {
        dots.clear()
        active = false
    }

    /** Remaining opacity, 0 once the fade is over (which also drops the snapshot). */
    fun factor(now: Long): Float {
        if (!active) return 0f
        val remaining = 1f - (now - startedAt).toFloat() / durationMs
        if (remaining <= 0f) {
            clear()
            return 0f
        }
        return remaining.coerceAtMost(1f)
    }

    companion object {
        const val DEFAULT_DURATION_MS = 500L
    }
}
