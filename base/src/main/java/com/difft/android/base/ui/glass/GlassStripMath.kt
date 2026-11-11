package com.difft.android.base.ui.glass


enum class SystemBarEdge { TOP, BOTTOM }


object GlassStripMath {
    fun ramp(distanceFromEdgePx: Float, extentPx: Float): Float =
        if (extentPx > 0f) (1f - distanceFromEdgePx / extentPx).coerceIn(0f, 1f) else 0f

    fun insetFraction(insetPx: Float, extentPx: Float): Float =
        if (extentPx > 0f) (insetPx / extentPx).coerceIn(0f, 1f) else 0f
}
