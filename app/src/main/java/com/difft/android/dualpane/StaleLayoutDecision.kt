package com.difft.android.dualpane

import android.content.res.Configuration
import com.difft.android.base.utils.OrientationPolicy

/**
 * Detects the #1454 state: a fold left IndexActivity with its dual-pane tree on a panel that
 * cannot host dual pane, because the platform resized the window but withheld the activity's
 * Configuration (HyperOS: window 1168x1712 px, configuration still 860x608 dp, no relaunch).
 *
 * Sizes are compared in px: the configuration's dp times its own density is the window it
 * describes, so a display-size (DPI) change, where dp and density move together, is not stale.
 */
internal object StaleLayoutDecision {

    /** The `layout-w673dp-h480dp` qualifier; pinned by `PaneGateResourceAgreementTest`. */
    const val DUAL_PANE_MIN_WIDTH_DP = 673
    const val DUAL_PANE_MIN_HEIGHT_DP = 480

    fun decide(
        treeIsDualPane: Boolean,
        cfgSwPx: Int,
        cfgLongPx: Int,
        panelShortPx: Int,
        panelLongPx: Int,
        panelShortDp: Int,
        panelLongDp: Int,
        marginPx: Int,
    ): Boolean = treeIsDualPane &&
        exceedsPanel(cfgSwPx, cfgLongPx, panelShortPx, panelLongPx, marginPx) &&
        // In landscape the long side is the width; portrait needs even more. Below this in both
        // rotations the panel can never host the dual-pane tree.
        (panelLongDp < DUAL_PANE_MIN_WIDTH_DP || panelShortDp < DUAL_PANE_MIN_HEIGHT_DP)

    /** The configuration describes a window larger than the physical panel: a size no window can have. */
    fun exceedsPanel(cfgSwPx: Int, cfgLongPx: Int, panelShortPx: Int, panelLongPx: Int, marginPx: Int): Boolean =
        panelShortPx > 0 && panelLongPx > 0 &&
            (cfgSwPx - panelShortPx >= marginPx || cfgLongPx - panelLongPx >= marginPx)

    /** [decide] for a live [configuration] and [panel]. */
    fun isStale(treeIsDualPane: Boolean, configuration: Configuration, panel: OrientationPolicy.HostPanel): Boolean {
        val px = ConfigPx.of(configuration)
        return decide(
            treeIsDualPane, px.swPx, px.longPx, panel.shortPx, panel.longPx, panel.shortDp, panel.longDp, marginPx(panel),
        )
    }

    /** [exceedsPanel] for a live [configuration]; false is "the configuration fits the panel". */
    fun exceeds(configuration: Configuration, panel: OrientationPolicy.HostPanel): Boolean {
        val px = ConfigPx.of(configuration)
        return exceedsPanel(px.swPx, px.longPx, panel.shortPx, panel.longPx, marginPx(panel))
    }

    fun marginPx(panel: OrientationPolicy.HostPanel): Int =
        (OrientationPolicy.STALE_WINDOW_MARGIN_DP * panel.density).toInt()

    /** The window a [Configuration] describes, in px at its own density. */
    data class ConfigPx(val widthPx: Int, val heightPx: Int, val swPx: Int) {
        val longPx: Int get() = maxOf(widthPx, heightPx)

        companion object {
            fun of(configuration: Configuration): ConfigPx {
                val density = configuration.densityDpi / BASELINE_DPI
                return ConfigPx(
                    widthPx = (configuration.screenWidthDp * density).toInt(),
                    heightPx = (configuration.screenHeightDp * density).toInt(),
                    swPx = (configuration.smallestScreenWidthDp * density).toInt(),
                )
            }
        }
    }

    private const val BASELINE_DPI = 160f
}
