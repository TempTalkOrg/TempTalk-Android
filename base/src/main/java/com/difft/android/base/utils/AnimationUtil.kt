package com.difft.android.base.utils

import android.content.Context
import android.provider.Settings







fun isAnimationsDisabled(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}
