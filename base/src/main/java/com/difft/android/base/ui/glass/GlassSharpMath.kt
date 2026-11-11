package com.difft.android.base.ui.glass

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min


internal data class GlassSharpRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal object GlassSharpMath {
    
    fun region(
        left: Float, top: Float, right: Float, bottom: Float,
        sourceLeft: Int, sourceTop: Int, sourceRight: Int, sourceBottom: Int,
    ): GlassSharpRegion? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            right <= left || bottom <= top || sourceRight <= sourceLeft || sourceBottom <= sourceTop
        ) return null
        val cropLeft = max(floor(left).toInt(), sourceLeft)
        val cropTop = max(floor(top).toInt(), sourceTop)
        val cropRight = min(ceil(right).toInt(), sourceRight)
        val cropBottom = min(ceil(bottom).toInt(), sourceBottom)
        return if (cropRight > cropLeft && cropBottom > cropTop) {
            GlassSharpRegion(cropLeft, cropTop, cropRight, cropBottom)
        } else null
    }
}
