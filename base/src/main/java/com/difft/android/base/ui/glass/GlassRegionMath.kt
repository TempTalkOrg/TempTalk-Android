package com.difft.android.base.ui.glass

import kotlin.math.ceil
import kotlin.math.floor


internal object GlassRegionMath {
    fun blurMargin(density: Float): Int = ceil(
        GlassTuning.BLUR_RADIUS_DP * density * GlassTuning.BLUR_SAMPLE_MARGIN_MULTIPLIER,
    ).toInt().coerceAtLeast(0)

    



    fun merge(
        requests: Iterable<GlassSharpRegion>,
        source: GlassSharpRegion,
        margin: Int,
        alignment: Int = GlassTuning.DOWNSAMPLE.toInt(),
    ): List<GlassSharpRegion> {
        val result = arrayListOf<GlassSharpRegion>()
        val grid = alignment.coerceAtLeast(1).toDouble()
        val guard = margin.coerceAtLeast(0).toDouble()
        for (request in requests) {
            if (request.right <= request.left || request.bottom <= request.top || !intersects(request, source)) continue
            var region = GlassSharpRegion(
                (floor((request.left - guard) / grid) * grid).toInt().coerceAtLeast(source.left),
                (floor((request.top - guard) / grid) * grid).toInt().coerceAtLeast(source.top),
                (ceil((request.right + guard) / grid) * grid).toInt().coerceAtMost(source.right),
                (ceil((request.bottom + guard) / grid) * grid).toInt().coerceAtMost(source.bottom),
            )
            if (region.right <= region.left || region.bottom <= region.top) continue
            
            var index = 0
            while (index < result.size) {
                val other = result[index]
                if (touches(region, other)) {
                    region = GlassSharpRegion(minOf(region.left, other.left), minOf(region.top, other.top),
                        maxOf(region.right, other.right), maxOf(region.bottom, other.bottom))
                    result.removeAt(index)
                    index = 0
                } else index++
            }
            result += region
        }
        result.sortWith(compareBy<GlassSharpRegion> { it.top }.thenBy { it.left })
        return result
    }

    fun intersects(a: GlassSharpRegion, b: GlassSharpRegion): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    
    fun covers(region: GlassSharpRegion, left: Float, top: Float, right: Float, bottom: Float): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top && left >= region.left && top >= region.top &&
            right <= region.right && bottom <= region.bottom

    private fun touches(a: GlassSharpRegion, b: GlassSharpRegion): Boolean =
        a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top
}
