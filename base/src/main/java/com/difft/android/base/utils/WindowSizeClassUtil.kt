package com.difft.android.base.utils

import android.app.Activity
import android.view.View
import androidx.window.layout.WindowMetricsCalculator







object WindowSizeClassUtil {

    








    enum class WindowWidthSizeClass {
        
        COMPACT,
        
        MEDIUM,
        
        EXPANDED
    }

    






    const val EXPANDED_WIDTH_THRESHOLD_DP = 840

    









    const val DUAL_PANE_MIN_WIDTH_DP = 673

    




    const val MIN_HEIGHT_FOR_DUAL_PANE_DP = 480

    


    fun computeWindowWidthSizeClass(activity: Activity): WindowWidthSizeClass {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val widthDp = metrics.bounds.width() / activity.resources.displayMetrics.density

        return when {
            widthDp < 600 -> WindowWidthSizeClass.COMPACT
            widthDp < 840 -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }
    }


    



    fun getWindowWidthPx(activity: Activity): Int {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        return metrics.bounds.width()
    }

    



    fun getWindowHeightPx(activity: Activity): Int {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        return metrics.bounds.height()
    }
}







fun View.windowWidthPx(): Int =
    (context as? Activity)?.let { WindowSizeClassUtil.getWindowWidthPx(it) }
        ?: resources.displayMetrics.widthPixels





fun View.windowHeightPx(): Int =
    (context as? Activity)?.let { WindowSizeClassUtil.getWindowHeightPx(it) }
        ?: resources.displayMetrics.heightPixels