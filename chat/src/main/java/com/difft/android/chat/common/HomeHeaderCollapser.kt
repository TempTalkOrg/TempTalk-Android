package com.difft.android.chat.common

import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout









object HomeHeaderCollapser {

    
    const val SHORT_WINDOW_MAX_HEIGHT_DP = 700

    



    fun isEligible(activity: Activity): Boolean =
        !activity.isInMultiWindowMode &&
            activity.resources.configuration.screenHeightDp < SHORT_WINDOW_MAX_HEIGHT_DP

    
    fun isFoldable(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    





    fun bind(appBar: AppBarLayout, header: View, enabled: Boolean) {
        (header.layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
            params.scrollFlags = if (enabled) AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL else 0
            header.layoutParams = params
        }
        (appBar.layoutParams as? CoordinatorLayout.LayoutParams)?.let { params ->
            params.behavior = AppBarLayout.Behavior().apply {
                setDragCallback(object : AppBarLayout.Behavior.DragCallback() {
                    override fun canDrag(appBarLayout: AppBarLayout): Boolean = false
                })
            }
            appBar.layoutParams = params
        }
    }

    




    fun collapseRangePx(header: View, enabled: Boolean): Int =
        if (enabled) header.layoutParams.height.coerceAtLeast(0) else 0
}
