package com.difft.android.base.widget

/**
 * Timing of the transient pattern feedback, independent of drawing.
 *
 * Path shown (AOSP LockPatternView fade): every segment holds for [fadeDelayMs] after it is drawn,
 * then is wiped from its start towards its end while fading out over [fadeMs]; selected dots stay lit.
 * Path hidden: every dot flashes for [pulseMs] from the moment it is selected, each on its own clock,
 * so reaching the next dot does not cut the previous flash short. Positions are indices into the gesture's dot
 * sequence; segment `i` joins dots `i` and `i + 1`.
 */
internal class PatternTrailState(
    private val fadeDelayMs: Long = DEFAULT_FADE_DELAY_MS,
    private val fadeMs: Long = DEFAULT_FADE_MS,
    private val pulseMs: Long = DEFAULT_PULSE_MS,
) {
    // Time each dot was added; segment i starts when dot i + 1 is added.
    private val addedAt = ArrayList<Long>()

    private val dotCount get() = addedAt.size

    fun reset() {
        addedAt.clear()
    }

    fun onDotAdded(now: Long) {
        addedAt.add(now)
    }

    /** Opacity of segment [index] while the path is shown. */
    fun segmentAlpha(index: Int, now: Long): Float =
        if (index in 0 until dotCount - 1) 1f - fadeProgress(addedAt[index + 1], now) else 0f

    /** How far segment [index] is wiped from its start, 0..1. */
    fun wipeProgress(index: Int, now: Long): Float =
        if (index in 0 until dotCount - 1) fadeProgress(addedAt[index + 1], now) else 0f

    /** Opacity of the flash on dot [index] while the path is hidden. */
    fun pulseAlpha(index: Int, now: Long): Float =
        if (index in 0 until dotCount) (1f - (now - addedAt[index]).toFloat() / pulseMs).coerceIn(0f, 1f) else 0f

    fun isAnimating(now: Long, pathHidden: Boolean): Boolean = when {
        dotCount == 0 -> false
        pathHidden -> pulseAlpha(dotCount - 1, now) > 0f
        else -> dotCount >= 2 && segmentAlpha(dotCount - 2, now) > 0f
    }

    private fun fadeProgress(drawnAt: Long, now: Long): Float =
        ((now - drawnAt - fadeDelayMs).toFloat() / fadeMs).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_FADE_DELAY_MS = 150L
        const val DEFAULT_FADE_MS = 500L
        const val DEFAULT_PULSE_MS = 500L
    }
}
