package com.difft.android.base.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding







object EdgeToEdgeUtils {

    
    fun Activity.setupEdgeToEdge() {
        if (this is ComponentActivity) {
            enableEdgeToEdge()
        }
    }

    













    fun applySystemBarsPadding(
        rootView: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
        applyHorizontal: Boolean = false,
        extraRight: () -> Int = { 0 },
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            
            val statusStripBottom = maxOf(
                insets.top,
                windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top,
            )
            val horizontal = if (cutoutReachesContent(windowInsets, statusStripBottom)) {
                insets
            } else {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            view.updatePadding(
                left = if (applyHorizontal) horizontal.left else 0,
                top = if (applyTop) view.safeTopInset(windowInsets) else 0,
                right = (if (applyHorizontal) horizontal.right else 0) + extraRight(),
                bottom = if (applyBottom) insets.bottom else 0
            )
            
            windowInsets
        }
        rootView.requestApplyInsets()
    }

    






    private fun cutoutReachesContent(windowInsets: WindowInsetsCompat, statusStripBottom: Int): Boolean {
        val cutout = windowInsets.displayCutout ?: return false
        val waterfall = cutout.waterfallInsets
        if (waterfall.left > 0 || waterfall.right > 0) return true
        return cutout.boundingRects.any { it.top >= statusStripBottom }
    }

    






    fun applySystemBarsAndImePadding(
        rootView: View,
        applyTop: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = if (applyTop) systemBars.top else 0,
                bottom = maxOf(systemBars.bottom, ime.bottom)
            )
            windowInsets
        }
        rootView.requestApplyInsets()
    }

    









    fun applyBottomInsetAsPadding(view: View) {
        val basePaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.updatePadding(bottom = basePaddingBottom + insets.bottom)
            windowInsets
        }
        view.requestApplyInsets()
    }

    





    fun getSystemBarsInsets(view: View): Pair<Int, Int> {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        return if (insets != null) {
            Pair(insets.top, insets.bottom)
        } else {
            Pair(0, 0)
        }
    }

    



    fun clearWindowInsetsListener(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
    }
}