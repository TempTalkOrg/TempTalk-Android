package com.difft.android.base.ui.glass

import kotlin.math.ceil


object GlassLensMath {
    fun progress(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    
    fun samplePadding(density: Float, style: GlassLensStyle): Int {
        val amount = style.amountDp.coerceAtLeast(0f) * density * if (style.chromatic) 2f else 1f
        val blur = style.blurRadiusDp.coerceAtLeast(0f) * density
        return ceil(amount + 3f * blur + GlassTuning.LENS_SAMPLE_GUARD_PX).toInt()
    }
}
