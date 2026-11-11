package com.difft.android.base.widget

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min





internal class PatternGrid(val dotCount: Int) {
    val size = dotCount * dotCount
    val centerX = FloatArray(size)
    val centerY = FloatArray(size)

    fun row(id: Int) = id / dotCount

    fun column(id: Int) = id % dotCount

    
    fun dotAt(x: Float, y: Float, radius: Float): Int {
        for (id in 0 until size) {
            if (hypot(x - centerX[id], y - centerY[id]) <= radius) return id
        }
        return -1
    }

    
    fun nearestDotAt(x: Float, y: Float, radius: Float): Int {
        var nearest = -1
        var nearestDistance = radius
        for (id in 0 until size) {
            val distance = hypot(x - centerX[id], y - centerY[id])
            if (distance <= nearestDistance) {
                nearest = id
                nearestDistance = distance
            }
        }
        return nearest
    }

    fun distanceToSegment(id: Int, x1: Float, y1: Float, x2: Float, y2: Float): Float =
        segmentDistance(centerX[id], centerY[id], x1, y1, x2, y2)

    
    fun projectionOnSegment(id: Int, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return 0f
        val t = ((centerX[id] - x1) * dx + (centerY[id] - y1) * dy) / lengthSquared
        return max(0f, min(1f, t))
    }

    companion object {
        
        fun edgePoint(centerX: Float, centerY: Float, targetX: Float, targetY: Float, radius: Float): Pair<Float, Float> {
            val dx = targetX - centerX
            val dy = targetY - centerY
            val distance = hypot(dx, dy)
            if (distance == 0f) return Pair(centerX, centerY)
            return Pair(centerX + dx / distance * radius, centerY + dy / distance * radius)
        }

        
        fun segmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) return hypot(px - x1, py - y1)
            val t = max(0f, min(1f, ((px - x1) * dx + (py - y1) * dy) / lengthSquared))
            return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
        }
    }
}
