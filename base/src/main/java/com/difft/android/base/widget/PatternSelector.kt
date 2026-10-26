package com.difft.android.base.widget

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Builds the dot sequence of a gesture from touch samples.
 *
 * Before the first dot the start is acquired like the legacy rule (generous radius around the touch
 * point, plus a short slide towards a nearby dot). Afterwards every sample is tested as a segment
 * from the previous sample, so a fast swipe cannot skip a dot it crossed; a dot two cells away in a
 * straight line pulls in the unused middle dot first. A dot is used at most once.
 */
internal class PatternSelector(
    private val grid: PatternGrid,
    private val hitRadius: Float,
    private val startRadius: Float,
    private val startSlideMaxDistance: Float,
) {
    private val sequence = ArrayList<Int>()
    private var started = false
    private var downX = 0f
    private var downY = 0f
    private var prevX = 0f
    private var prevY = 0f

    val selected: List<Int> get() = sequence

    fun reset() {
        sequence.clear()
        started = false
    }

    /** Returns the dots newly selected by this sample, in selection order. */
    fun down(x: Float, y: Float): List<Int> {
        reset()
        downX = x
        downY = y
        prevX = x
        prevY = y
        // The start radius is generous and overlaps neighbours: take the nearest dot.
        val start = grid.nearestDotAt(x, y, startRadius)
        if (start < 0) return emptyList()
        started = true
        sequence.add(start)
        return listOf(start)
    }

    /** Feed samples in time order (historical first, then the current one). */
    fun move(x: Float, y: Float): List<Int> {
        val added = ArrayList<Int>(2)
        if (!started) {
            var start = grid.nearestDotAt(x, y, startRadius)
            if (start < 0) start = findDotNearStartPath(x, y)
            if (start >= 0) {
                started = true
                sequence.add(start)
                added.add(start)
            }
        } else {
            val hits = (0 until grid.size)
                .filter { it !in sequence && grid.distanceToSegment(it, prevX, prevY, x, y) <= hitRadius }
                .sortedWith(
                    compareBy(
                        { grid.projectionOnSegment(it, prevX, prevY, x, y) },
                        { grid.distanceToSegment(it, prevX, prevY, x, y) },
                    ),
                )
            for (id in hits) {
                if (id in sequence) continue
                val middle = middleDot(sequence.last(), id)
                if (middle >= 0 && middle !in sequence) {
                    sequence.add(middle)
                    added.add(middle)
                }
                sequence.add(id)
                added.add(id)
            }
        }
        prevX = x
        prevY = y
        return added
    }

    private fun findDotNearStartPath(x: Float, y: Float): Int {
        if (hypot(x - downX, y - downY) > startSlideMaxDistance) return -1
        for (id in 0 until grid.size) {
            if (hypot(grid.centerX[id] - downX, grid.centerY[id] - downY) > startRadius) continue
            if (grid.distanceToSegment(id, downX, downY, x, y) <= startRadius / 2) return id
        }
        return -1
    }

    /** The cell between two dots exactly two apart in a row, column or diagonal, else -1. */
    private fun middleDot(from: Int, to: Int): Int {
        val dRow = grid.row(to) - grid.row(from)
        val dColumn = grid.column(to) - grid.column(from)
        if (abs(dRow) > 2 || abs(dColumn) > 2) return -1
        if (dRow % 2 != 0 || dColumn % 2 != 0 || (dRow == 0 && dColumn == 0)) return -1
        return (grid.row(from) + dRow / 2) * grid.dotCount + grid.column(from) + dColumn / 2
    }
}
