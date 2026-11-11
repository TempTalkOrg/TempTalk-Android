package com.difft.android.base.utils

import android.view.View
import androidx.core.text.layoutDirection
import java.util.Locale

object RtlUtils {

    fun isRtl(): Boolean {
        return Locale.getDefault().layoutDirection == View.LAYOUT_DIRECTION_RTL
    }
}
