



package com.difft.android.home

import androidx.compose.animation.core.EaseOut
import kotlin.math.abs
import kotlin.math.sign


internal object HomeTabPanelMath {
    fun offset(displacement: Float, extent: Float, maximum: Float): Float {
        if (!displacement.isFinite() || !extent.isFinite() || extent <= 0f || !maximum.isFinite()) return 0f
        val fraction = (displacement / extent).coerceIn(-1f, 1f)
        return maximum.coerceAtLeast(0f) * fraction.sign * EaseOut.transform(abs(fraction))
    }

    fun scale(progress: Float, extent: Float, growth: Float): Float {
        if (!extent.isFinite() || extent <= 0f || !growth.isFinite() || !progress.isFinite()) return 1f
        return 1f + growth.coerceAtLeast(0f) / extent * progress.coerceIn(0f, 1f)
    }

    fun selectedContentScale(progress: Float): Float =
        1f + 0.2f * (if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f)
}
