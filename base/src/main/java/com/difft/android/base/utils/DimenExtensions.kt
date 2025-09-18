@file:JvmName("DimenUtils")

package com.difft.android.base.utils

val Int.dp: Int
    inline get() = (this * application.resources.displayMetrics.density).toInt()

val Float.dp: Float
    inline get() = this * application.resources.displayMetrics.density
