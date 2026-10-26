package com.difft.android.base.widget

import kotlin.math.hypot

/**
 * The dot-selection rule patterns were saved with before segment hit testing: only the current
 * position of each event counts, a generous radius picks the first dot, later dots need a sample
 * within [hitRadius]. Kept unchanged so a stored pattern drawn under it can still be matched.
 */
internal class LegacyPatternTracker(
    private val grid: PatternGrid,
    private val hitRadius: Float,
    private val startRadius: Float,
    private val startSlideMaxDistance: Float,
) {
    private val sequence = ArrayList<Int>()
    private var started = false
    private var downX = 0f
    private var downY = 0f

    val selected: List<Int> get() = sequence

    fun reset() {
        sequence.clear()
        started = false
    }

    fun down(x: Float, y: Float) {
        reset()
        downX = x
        downY = y
        val dot = grid.dotAt(x, y, startRadius)
        if (dot >= 0) {
            started = true
            select(dot)
        }
    }

    fun move(x: Float, y: Float) {
        if (!started) {
            var dot = grid.dotAt(x, y, startRadius)
            if (dot < 0) dot = findDotNearStartPath(x, y)
            if (dot >= 0) {
                started = true
                select(dot)
            }
        } else {
            val dot = grid.dotAt(x, y, hitRadius)
            if (dot >= 0) select(dot)
        }
    }

    private fun select(dot: Int) {
        if (dot !in sequence) sequence.add(dot)
    }

    private fun findDotNearStartPath(x: Float, y: Float): Int {
        if (hypot(x - downX, y - downY) > startSlideMaxDistance) return -1
        for (id in 0 until grid.size) {
            if (hypot(grid.centerX[id] - downX, grid.centerY[id] - downY) > startRadius) continue
            if (grid.distanceToSegment(id, downX, downY, x, y) <= startRadius / 2) return id
        }
        return -1
    }
}
