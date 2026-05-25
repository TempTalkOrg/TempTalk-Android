package com.difft.android.call.util

import android.content.res.Resources
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object ViewUtil {

    fun dpToPx(dp: Int): Int {
        return Math.round(dp * Resources.getSystem().displayMetrics.density)
    }

    fun pxToDp(px: Float): Float {
        return px / Resources.getSystem().displayMetrics.density
    }

    fun hideNavigationBar(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}