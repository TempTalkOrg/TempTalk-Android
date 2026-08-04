package com.difft.android.chat.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import androidx.annotation.ColorInt

@Suppress("DEPRECATION")
object WindowUtil {

    @JvmStatic
    fun clearLightNavigationBar(window: Window) {
        if (Build.VERSION.SDK_INT < 27) return

        clearSystemUiFlags(window, View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    @JvmStatic
    fun setLightNavigationBar(window: Window) {
        if (Build.VERSION.SDK_INT < 27) return

        setSystemUiFlags(window, View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    @JvmStatic
    fun setNavigationBarColor(activity: Activity, @ColorInt color: Int) {
        setNavigationBarColor(activity, activity.window, color)
    }

    @JvmStatic
    fun setNavigationBarColor(context: Context, window: Window, @ColorInt color: Int) {
        if (Build.VERSION.SDK_INT < 27) {
            window.navigationBarColor = ThemeUtil.getThemedColor(context, android.R.attr.navigationBarColor)
        } else {
            window.navigationBarColor = color
        }
    }

    @JvmStatic
    fun clearLightStatusBar(window: Window) {
        clearSystemUiFlags(window, View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
    }

    @JvmStatic
    fun setLightStatusBar(window: Window) {
        setSystemUiFlags(window, View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
    }

    @JvmStatic
    fun setStatusBarColor(window: Window, @ColorInt color: Int) {
        window.statusBarColor = color
    }

    @JvmStatic
    fun getStatusBarColor(window: Window): Int {
        return window.statusBarColor
    }

    private fun clearSystemUiFlags(window: Window, flags: Int) {
        val view = window.decorView
        var uiFlags = view.systemUiVisibility

        uiFlags = uiFlags and flags.inv()
        view.systemUiVisibility = uiFlags
    }

    private fun setSystemUiFlags(window: Window, flags: Int) {
        val view = window.decorView
        var uiFlags = view.systemUiVisibility

        uiFlags = uiFlags or flags
        view.systemUiVisibility = uiFlags
    }
}
