package com.difft.android.dualpane

import android.content.res.Configuration
import com.difft.android.base.utils.OrientationPolicy









internal object StaleLayoutDecision {

    
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
        
        
        (panelLongDp < DUAL_PANE_MIN_WIDTH_DP || panelShortDp < DUAL_PANE_MIN_HEIGHT_DP)

    
    fun exceedsPanel(cfgSwPx: Int, cfgLongPx: Int, panelShortPx: Int, panelLongPx: Int, marginPx: Int): Boolean =
        panelShortPx > 0 && panelLongPx > 0 &&
            (cfgSwPx - panelShortPx >= marginPx || cfgLongPx - panelLongPx >= marginPx)

    
    fun isStale(treeIsDualPane: Boolean, configuration: Configuration, panel: OrientationPolicy.HostPanel): Boolean {
        val px = ConfigPx.of(configuration)
        return decide(
            treeIsDualPane, px.swPx, px.longPx, panel.shortPx, panel.longPx, panel.shortDp, panel.longDp, marginPx(panel),
        )
    }

    
    fun exceeds(configuration: Configuration, panel: OrientationPolicy.HostPanel): Boolean {
        val px = ConfigPx.of(configuration)
        return exceedsPanel(px.swPx, px.longPx, panel.shortPx, panel.longPx, marginPx(panel))
    }

    fun marginPx(panel: OrientationPolicy.HostPanel): Int =
        (OrientationPolicy.STALE_WINDOW_MARGIN_DP * panel.density).toInt()

    
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
