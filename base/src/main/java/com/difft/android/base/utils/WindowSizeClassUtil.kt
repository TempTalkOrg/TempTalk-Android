package com.difft.android.base.utils

import android.app.Activity
import android.view.View
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
     * Threshold for expanded width in dp
     *
     * 840dp is the official Material Design 3 recommended breakpoint for expanded layouts.
     * This is the M3 width-class boundary only — **not** the dual-pane gate; see
     * [DUAL_PANE_MIN_WIDTH_DP].
     */
    const val EXPANDED_WIDTH_THRESHOLD_DP = 840

    /**
     * Minimum width in dp for dual-pane layout
     *
     * DECLARED value only. The gate is ENFORCED by the resource directory
     * `app/src/main/res/layout-w673dp-h480dp/` — that qualifier is what decides whether
     * `activity_index.xml` inflates a `detail_pane`, so dual-pane mode is derived from the
     * inflated view tree, not from this constant. Do NOT add a runtime check against it:
     * `WindowMetricsCalculator` bounds include system-decoration insets while `w<N>dp` matches
     * `Configuration.screenWidthDp` (the available width), so the two disagree near the
     * boundary and a mixed gate yields dual-pane branches running against a view tree that
     * has no detail pane.
     *
     * When runtime code needs the EXPECTATION — would the current configuration inflate a
     * `detail_pane`? — it reads the sanctioned mirror `R.bool.dual_pane_layout_active`
     * (`app/src/main/res/values/bools.xml` + `values-w673dp-h480dp/bools.xml`), never this
     * constant: that bool's qualifier set is identical to the layout directory's, so the same
     * resolver answers it from the same `Configuration` at the same instant as inflation and
     * the two cannot disagree by construction. `PaneGateResourceAgreementTest` pins
     * `getBoolean(dual_pane_layout_active) == (findViewById(detail_pane) != null)` across the
     * gate boundaries, and `DualPaneBudgetResourceInvariantTest` pins that bool against this
     * constant and [MIN_HEIGHT_FOR_DUAL_PANE_DP], so the directories cannot be renamed apart.
     *
     * 673dp is the pane-budget feasibility floor: it must equal
     * `dual_pane_rail_width` + `dual_pane_divider_width` + `dual_pane_list_min_width` +
     * `dual_pane_detail_min_width` from `app/src/main/res/values/dimens.xml`
     * (72 + 1 + 280 + 320), i.e. the narrowest window where both panes still meet their
     * declared minimums. The equality and the directory name are test-pinned so they cannot
     * drift apart.
     */
    const val DUAL_PANE_MIN_WIDTH_DP = 673

    /**
     * Minimum height in dp for dual-pane layout
     *
     * 480dp corresponds to Material Design 3 WindowHeightSizeClass.MEDIUM threshold.
     * This prevents dual-pane mode on folded screens in landscape orientation,
     * where width may exceed [DUAL_PANE_MIN_WIDTH_DP] but height is too short for
     * comfortable use. Example: Samsung Z TriFold folded landscape is ~955dp x ~409dp
     *
     * DECLARED value only, like [DUAL_PANE_MIN_WIDTH_DP]: it is ENFORCED by the `h480dp`
     * component of the `layout-w673dp-h480dp/` qualifier, not by any runtime check — the
     * sanctioned runtime mirror is the `h480dp` half of the same
     * `R.bool.dual_pane_layout_active` described above.
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

    // shouldUseDualPaneLayout() was deleted deliberately: the resource qualifier
    // (layout-w673dp-h480dp/) is the single dual-pane enforcement mechanism, and a runtime
    // WindowMetrics-based check disagrees with it near the boundary (bounds include system
    // decoration). Former call-module callers use the sw600dp orientation policy
    // (R.bool.force_portrait_orientation) instead. Do not reintroduce a second gate.

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

/**
 * Get current window width in px via the View's host Activity.
 * Falls back to local displayMetrics if the View's context is not an Activity (rare,
 * e.g. an isolated test or a wrapped non-Activity context). Use this from Views
 * that need to know the current app window size on foldables / multi-window.
 */
fun View.windowWidthPx(): Int =
    (context as? Activity)?.let { WindowSizeClassUtil.getWindowWidthPx(it) }
        ?: resources.displayMetrics.widthPixels

/**
 * Get current window height in px via the View's host Activity.
 * Same fallback semantics as [windowWidthPx].
 */
fun View.windowHeightPx(): Int =
    (context as? Activity)?.let { WindowSizeClassUtil.getWindowHeightPx(it) }
        ?: resources.displayMetrics.heightPixels