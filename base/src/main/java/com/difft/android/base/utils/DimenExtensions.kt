@file:JvmName("DimenUtils")

package com.difft.android.base.utils

import com.difft.android.base.utils.density.UiDensity

val Int.dp: Int
    inline get() = (this * UiDensity.density).toInt()

val Float.dp: Float
    inline get() = this * UiDensity.density
