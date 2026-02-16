package com.difft.android.call.util

import android.content.res.Resources
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.view.WindowInsetsCompat

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

    /**
     * hide navigation bar
     */
    fun hideNavigationBar(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

}