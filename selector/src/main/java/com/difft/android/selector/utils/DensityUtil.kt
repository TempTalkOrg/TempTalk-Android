package com.difft.android.selector.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.immersive.RomUtils

object DensityUtil {

    /** Real screen width. */
    @JvmStatic
    fun getRealScreenWidth(context: Context): Int {
        if (context is Activity) {
            val wm = context.windowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wm.currentWindowMetrics.bounds.width()
            }
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        return point.x
    }

    /** Real screen height. */
    @JvmStatic
    fun getRealScreenHeight(context: Context): Int {
        if (context is Activity) {
            val wm = context.windowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wm.currentWindowMetrics.bounds.height()
            }
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        return point.y
    }

    /** Screen height excluding status/navigation bars. */
    @JvmStatic
    fun getScreenHeight(context: Context): Int {
        return getRealScreenHeight(context) - getStatusNavigationBarHeight(context)
    }

    private fun getStatusNavigationBarHeight(context: Context): Int {
        return if (isNavBarVisible(context)) {
            getStatusBarHeight(context) + getNavigationBarHeight(context)
        } else {
            getStatusBarHeight(context)
        }
    }

    @JvmStatic
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resources = Resources.getSystem()
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        try {
            if (resourceId > 0) {
                val sizeOne = context.resources.getDimensionPixelSize(resourceId)
                val sizeTwo = resources.getDimensionPixelSize(resourceId)
                result = if (sizeTwo >= sizeOne) {
                    sizeTwo
                } else {
                    val densityOne = context.resources.displayMetrics.density
                    val densityTwo = resources.displayMetrics.density
                    val f = sizeOne * densityTwo / densityOne
                    if (f >= 0) (f + 0.5f).toInt() else (f - 0.5f).toInt()
                }
            }
        } catch (ignored: Exception) {
            result = getStatusBarHeight()
        }
        return if (result == 0) dip2px(context, 26f) else result
    }

    @JvmStatic
    fun getStatusBarHeight(): Int {
        val resources = Resources.getSystem()
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return resources.getDimensionPixelSize(resourceId)
    }

    /** Call in onWindowFocusChanged to get the right result. */
    @JvmStatic
    fun isNavBarVisible(context: Context): Boolean {
        var isVisible = false
        if (context !is Activity) {
            return false
        }
        val window = context.window
        val decorView = window.decorView as ViewGroup
        var i = 0
        val count = decorView.childCount
        while (i < count) {
            val child = decorView.getChildAt(i)
            val id = child.id
            if (id != View.NO_ID) {
                val resourceEntryName = getResNameById(context, id)
                if ("navigationBarBackground" == resourceEntryName && child.visibility == View.VISIBLE) {
                    isVisible = true
                    break
                }
            }
            i++
        }
        if (isVisible) {
            // Samsung pre-OneUI2 (< Android 10) reports the nav bar as visible when the IME is shown
            // while the user has hidden the nav bar; fall back to the system setting there.
            if (RomUtils.isSamsung() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    return Settings.Global.getInt(context.contentResolver, "navigationbar_hide_bar_enabled") == 0
                } catch (ignore: Exception) {
                    L.w(ignore) { "[DensityUtil] isNavigationBarVisible check failed" }
                }
            }
            val visibility = decorView.systemUiVisibility
            isVisible = (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
        }
        return isVisible
    }

    private fun getResNameById(context: Context, id: Int): String {
        return try {
            context.resources.getResourceEntryName(id)
        } catch (ignore: Exception) {
            ""
        }
    }

    @JvmStatic
    fun getNavigationBarHeight(context: Context): Int {
        val res = context.resources
        val mInPortrait = res.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isNavBarVisible(context)) {
            val key = if (mInPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
            return getInternalDimensionSize(context, key)
        }
        return 0
    }

    private fun getInternalDimensionSize(context: Context, key: String): Int {
        try {
            val resourceId = Resources.getSystem().getIdentifier(key, "dimen", "android")
            if (resourceId > 0) {
                val sizeOne = context.resources.getDimensionPixelSize(resourceId)
                val sizeTwo = Resources.getSystem().getDimensionPixelSize(resourceId)
                return if (sizeTwo >= sizeOne) {
                    sizeTwo
                } else {
                    val densityOne = context.resources.displayMetrics.density
                    val densityTwo = Resources.getSystem().displayMetrics.density
                    val f = sizeOne * densityTwo / densityOne
                    if (f >= 0) (f + 0.5f).toInt() else (f - 0.5f).toInt()
                }
            }
        } catch (ignored: Resources.NotFoundException) {
            return 0
        }
        return 0
    }

    @JvmStatic
    fun dip2px(context: Context, dpValue: Float): Int {
        val scale = context.applicationContext.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }
}
