package com.difft.android.base.utils.density

import android.content.res.Resources







object UiDensity {

    @Volatile
    private var densityDpi: Int = 0

    
    val density: Float
        get() = densityDpi.takeIf { it > 0 }?.let { it / BASELINE_DPI } ?: Resources.getSystem().displayMetrics.density

    
    val scale: Float
        get() = density / Resources.getSystem().displayMetrics.density

    fun update(densityDpi: Int) {
        if (densityDpi > 0) this.densityDpi = densityDpi
    }

    private const val BASELINE_DPI = 160f
}
