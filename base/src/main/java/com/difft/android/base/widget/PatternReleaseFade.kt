package com.difft.android.base.widget






internal class PatternReleaseFade(private val durationMs: Long = DEFAULT_DURATION_MS) {

    
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
