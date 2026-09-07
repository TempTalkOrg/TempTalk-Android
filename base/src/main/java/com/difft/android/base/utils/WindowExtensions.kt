package com.difft.android.base.utils

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive navigation-bar policy shared by the call Activity window and the dialog windows that
 * bottom sheets open on top of it: hide the bar, let a swipe reveal it transiently.
 */
fun Window.hideNavigationBar() {
    WindowCompat.getInsetsController(this, decorView).apply {
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
