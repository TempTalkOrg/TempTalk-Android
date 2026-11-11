package com.difft.android.chat.ui

import kotlin.math.max
import kotlin.math.min


internal data class PxBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}








internal data class AttachmentMenuPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pivotX: Float,
    val pivotY: Float,
) {
    companion object {
        const val MAX_WIDTH_DP = 248f
        const val GAP_DP = 8f
        const val HEIGHT_RESERVE_DP = 32f

        fun width(area: PxBox, density: Float): Int =
            min(MAX_WIDTH_DP * density, area.width - 2 * GAP_DP * density).toInt().coerceAtLeast(0)

        fun place(source: PxBox, area: PxBox, contentHeight: Int, density: Float): AttachmentMenuPlacement {
            val gap = (GAP_DP * density).toInt()
            val width = width(area, density)
            val maxHeight = (area.height - source.height - HEIGHT_RESERVE_DP * density).toInt().coerceAtLeast(0)
            val height = min(contentHeight, maxHeight)
            val x = (source.left - gap).coerceAtMost(area.right - gap - width).coerceAtLeast(area.left + gap)
            val y = max(source.top - gap - height, area.top + gap)
            return AttachmentMenuPlacement(
                x = x,
                y = y,
                width = width,
                height = height,
                pivotX = (source.centerX - x).toFloat(),
                pivotY = (source.centerY - y).toFloat(),
            )
        }
    }
}
