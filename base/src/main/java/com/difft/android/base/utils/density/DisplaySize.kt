package com.difft.android.base.utils.density

import kotlin.math.abs






enum class DisplaySize(val scale: Float) {
    SMALL(0.9f),
    STANDARD(1f),
    LARGE(1.15f),
    ;

    companion object {
        



        fun closestTo(systemRatio: Float): DisplaySize = entries.minBy { abs(it.scale - systemRatio) }
    }
}
