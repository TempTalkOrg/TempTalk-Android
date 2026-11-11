package com.difft.android.chat.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager









@Suppress("DEPRECATION")
class FullscreenHelper(private val activity: Activity, suppressShowSystemUI: Boolean) {

    init {
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (!suppressShowSystemUI) {
            showSystemUI()
        }
    }

    fun showSystemUI() {
        showSystemUI(activity.window)
    }

    companion object {
        @JvmStatic
        fun showSystemUI(window: Window) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }
}
