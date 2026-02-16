package com.difft.android.base.utils

import android.app.Activity
import androidx.window.layout.WindowMetricsCalculator

/**
 * Window size class utility for adaptive layouts
 * Following Material Design 3 guidelines for window size classes
 *
 * @see <a href="https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes">Window size classes</a>
 */
object WindowSizeClassUtil {

    /**
     * Window width size classes
     * Based on Material Design 3 official breakpoints:
     * - Compact: < 600dp (phones)
     * - Medium: 600dp - 840dp (tablets portrait, foldables)
     * - Expanded: >= 840dp (tablets landscape, large foldables, desktop)
     *
     * @see <a href="https://m3.material.io/foundations/layout/applying-layout/window-size-classes">Material Design 3 Window Size Classes</a>
     */
    enum class WindowWidthSizeClass {
        /** Width < 600dp - Phones in portrait */
        COMPACT,
        /** 600dp <= Width < 840dp - Tablets in portrait, foldables */
        MEDIUM,
        /** Width >= 840dp - Tablets in landscape, large foldables, desktop */
        EXPANDED
    }

    /**
     * Threshold for expanded width in dp (dual-pane layout threshold)
     *
     * 840dp is the official Material Design 3 recommended breakpoint for expanded layouts.
     * This enables dual-pane (list + detail) layout on large screens.
     */
    const val EXPANDED_WIDTH_THRESHOLD_DP = 840

    /**
     * Minimum height in dp for dual-pane layout
     *
     * 480dp corresponds to Material Design 3 WindowHeightSizeClass.MEDIUM threshold.
     * This prevents dual-pane mode on folded screens in landscape orientation,
     * where width may exceed 840dp but height is too short for comfortable use.
     * Example: Samsung Z TriFold folded landscape is ~955dp x ~409dp
     */
    const val MIN_HEIGHT_FOR_DUAL_PANE_DP = 480

    /**
     * Calculate the current window width size class
     */
    fun computeWindowWidthSizeClass(activity: Activity): WindowWidthSizeClass {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val widthDp = metrics.bounds.width() / activity.resources.displayMetrics.density

        return when {
            widthDp < 600 -> WindowWidthSizeClass.COMPACT
            widthDp < 840 -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }
    }

    /**
     * Check if current window should use dual-pane layout (list + detail)
     * Requires both: width >= 840dp AND height >= 480dp
     */
    fun shouldUseDualPaneLayout(activity: Activity): Boolean {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val density = activity.resources.displayMetrics.density
        val widthDp = metrics.bounds.width() / density
        val heightDp = metrics.bounds.height() / density
        return widthDp >= EXPANDED_WIDTH_THRESHOLD_DP && heightDp >= MIN_HEIGHT_FOR_DUAL_PANE_DP
    }

    /**
     * Get the current window width in pixels
     * Use this instead of displayMetrics.widthPixels for correct multi-window support
     */
    fun getWindowWidthPx(activity: Activity): Int {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        return metrics.bounds.width()
    }

    /**
     * Get the current window height in pixels
     * Use this instead of displayMetrics.heightPixels for correct multi-window support
     */
    fun getWindowHeightPx(activity: Activity): Int {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        return metrics.bounds.height()
    }
}