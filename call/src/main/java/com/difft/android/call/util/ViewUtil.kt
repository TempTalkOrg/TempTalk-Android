package com.difft.android.call.util

import android.content.res.Resources

object ViewUtil {

    /**
     * Converts density-independent pixels (dp) to pixels (px)
     * @param dp The value in dp to convert
     * @return The value in pixels
     */
    fun dpToPx(dp: Int): Int {
        return Math.round(dp * Resources.getSystem().displayMetrics.density)
    }

    fun pxToDp(px: Float): Float {
        return px / Resources.getSystem().displayMetrics.density
    }
}