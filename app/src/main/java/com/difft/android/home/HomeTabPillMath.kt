package com.difft.android.home

import kotlin.math.abs
import kotlin.math.roundToInt


internal object HomeTabPillMath {
    fun clampIndex(index: Float, count: Int): Float =
        if (index.isFinite()) index.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat()) else 0f

    fun pointerIndex(coordinate: Float, slotExtent: Float, count: Int, reverse: Boolean): Float {
        if (slotExtent <= 0f) return 0f
        val logical = if (reverse) count * slotExtent - coordinate else coordinate
        return clampIndex(logical / slotExtent - 0.5f, count)
    }

    fun dragIndex(index: Float, delta: Float, slotExtent: Float, count: Int, reverse: Boolean): Float =
        if (slotExtent > 0f) clampIndex(index + delta / slotExtent * if (reverse) -1f else 1f, count)
        else clampIndex(index, count)

    fun nearestIndex(index: Float, count: Int): Int = clampIndex(index, count).roundToInt()

    fun offset(index: Float, slotExtent: Float, count: Int): Float = clampIndex(index, count) * slotExtent

    fun scale(velocity: Float, pressed: Float, result: PillScale = PillScale(1f, 1f)): PillScale {
        val speed = if (velocity.isNaN()) 0f else (abs(velocity) / SPEED_FOR_MAX_STRETCH).coerceIn(0f, 1f)
        val press = 1f + pressed.coerceIn(0f, 1f) * PRESSED_GROWTH
        result.axis = press * (1f + speed * MAX_STRETCH)
        result.cross = press * (1f - speed * CROSS_COMPRESSION)
        return result
    }

    private const val SPEED_FOR_MAX_STRETCH = 8f
    private const val MAX_STRETCH = 0.18f
    private const val CROSS_COMPRESSION = 0.06f
    private const val PRESSED_GROWTH = 0.035f
}

internal data class PillScale(var axis: Float, var cross: Float)
