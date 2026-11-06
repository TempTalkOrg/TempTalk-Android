package com.difft.android.chat.common

import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout

/**
 * Lets a home page's title row collapse with its list's nested scroll and come back when the
 * list returns to its top edge, the way iOS minimises the navigation bar on a foldable
 * (`UIBarMinimization`: on scroll down, restore at the scroll edge).
 * The row is an [AppBarLayout] child; the collapse is driven by the list's own nested scroll,
 * so it tracks the finger with no relayout or extra animation. Only a short window gets the
 * scroll flag ([isEligible]); elsewhere the row is a fixed header.
 */
object HomeHeaderCollapser {

    /** Short-window threshold: foldable cover screens (≈620–670dp tall) and 16:9 phones are in, unfolded panels out. */
    const val SHORT_WINDOW_MAX_HEIGHT_DP = 700

    /**
     * A short full-screen window, on any device: a foldable's cover screen, a 16:9 phone, a large
     * display-size setting or a landscape phone. Split-screen / freeform windows keep a fixed title.
     */
    fun isEligible(activity: Activity): Boolean =
        !activity.isInMultiWindowMode &&
            activity.resources.configuration.screenHeightDp < SHORT_WINDOW_MAX_HEIGHT_DP

    /** A foldable (the device has a hinge); the Me page shows its title row there and in two-pane. */
    fun isFoldable(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    /**
     * Gives [header] (a direct child of [appBar]) the collapse-on-scroll flag when [enabled].
     * The app bar never collapses by being dragged itself: AppBarLayout's default behaviour
     * would otherwise take over any vertical move that starts on the title or its buttons and
     * cancel their taps, on every window.
     */
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

    /**
     * Extra bottom room the page's list needs while the title is expanded: the scrolling body
     * is laid out taller by the collapse range, so without it the last rows (or a letter
     * index) would sit that far under the floating tab capsule. 0 when not [enabled].
     */
    fun collapseRangePx(header: View, enabled: Boolean): Int =
        if (enabled) header.layoutParams.height.coerceAtLeast(0) else 0
}
