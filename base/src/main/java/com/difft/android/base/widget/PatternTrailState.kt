package com.difft.android.base.widget










internal class PatternTrailState(
    private val fadeDelayMs: Long = DEFAULT_FADE_DELAY_MS,
    private val fadeMs: Long = DEFAULT_FADE_MS,
    private val pulseMs: Long = DEFAULT_PULSE_MS,
) {
    
    private val addedAt = ArrayList<Long>()

    private val dotCount get() = addedAt.size

    fun reset() {
        addedAt.clear()
    }

    fun onDotAdded(now: Long) {
        addedAt.add(now)
    }

    
    fun segmentAlpha(index: Int, now: Long): Float =
        if (index in 0 until dotCount - 1) 1f - fadeProgress(addedAt[index + 1], now) else 0f

    
    fun wipeProgress(index: Int, now: Long): Float =
        if (index in 0 until dotCount - 1) fadeProgress(addedAt[index + 1], now) else 0f

    
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
