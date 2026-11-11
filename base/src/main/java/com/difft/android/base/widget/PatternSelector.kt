package com.difft.android.base.widget

import kotlin.math.abs
import kotlin.math.hypot









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

    
    fun down(x: Float, y: Float): List<Int> {
        reset()
        downX = x
        downY = y
        prevX = x
        prevY = y
        
        val start = grid.nearestDotAt(x, y, startRadius)
        if (start < 0) return emptyList()
        started = true
        sequence.add(start)
        return listOf(start)
    }

    
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

    
    private fun middleDot(from: Int, to: Int): Int {
        val dRow = grid.row(to) - grid.row(from)
        val dColumn = grid.column(to) - grid.column(from)
        if (abs(dRow) > 2 || abs(dColumn) > 2) return -1
        if (dRow % 2 != 0 || dColumn % 2 != 0 || (dRow == 0 && dColumn == 0)) return -1
        return (grid.row(from) + dRow / 2) * grid.dotCount + grid.column(from) + dColumn / 2
    }
}
