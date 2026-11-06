package com.difft.android.dualpane

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.Surface
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy

/**
 * The last rung of the #1454 self-heal: when the platform keeps IndexActivity at the inner
 * panel's Configuration on the outer panel and neither the portrait request nor an in-place
 * arrival fixed it, [StaleLayoutMonitor] records the panel here and recreates the activity.
 * The new instance applies a size override derived from the panel in `attachBaseContext`, so
 * the one-pane layout and the `values-*` bands resolve for the real panel. A recreate alone
 * cannot help: the relaunch inflates from the same withheld configuration.
 *
 * The record is process-level and stays active while both attach-time conditions hold, so a
 * local recreation on the same stale panel (theme, locale) re-applies it; it is dropped the
 * first time either fails (the platform delivered a real configuration, or the panel changed),
 * and by [clear] on any rebuild that did not proceed.
 */
internal object StaleLayoutOverride {

    internal data class Record(val panelKey: String)

    @Volatile
    private var record: Record? = null

    val active: Record? get() = record

    fun record(panel: OrientationPolicy.HostPanel) {
        record = Record(panel.key)
    }

    fun clear() {
        record = null
    }

    /** Drops a record made for another panel: the device left that panel. */
    fun clearIfNotFor(panelKey: String) {
        if (record?.panelKey != panelKey) record = null
    }

    /**
     * Call from `attachBaseContext` right after `super.attachBaseContext(newBase)`, before
     * anything reads the activity's resources (`Activity.attach()` builds the window from them
     * right after). Applies the override only when the record's panel is still the host panel and
     * the incoming configuration still exceeds it; otherwise drops the record. Returns the panel
     * key the override was applied for, or null.
     */
    fun applyIfActive(
        activity: ContextThemeWrapper,
        newBase: Context,
        readPanel: (Context) -> OrientationPolicy.HostPanel? = ::readAttachPanel,
    ): String? {
        val current = record ?: return null
        return try {
            val panel = readPanel(newBase)
            // The base context, not the activity: reading the activity's resources would make
            // applyOverrideConfiguration throw.
            val incoming = activity.baseContext.resources.configuration
            when {
                panel == null || panel.key != current.panelKey -> drop(current, "panel")
                !StaleLayoutDecision.exceeds(incoming, panel) -> drop(current, "fits")
                else -> {
                    val override = sizeOverride(panel.widthPx, panel.heightPx, panel.rotation, incoming.densityDpi)
                    activity.applyOverrideConfiguration(override)
                    val line = "[IndexActivity] staleLayout override panel=${panel.key} " +
                        "cfgDp=${override.screenWidthDp}x${override.screenHeightDp}"
                    L.w { line }
                    current.panelKey
                }
            }
        } catch (e: Exception) {
            // A second override or an earlier resources read throws; the instance stays as inflated.
            val line = "[IndexActivity] staleLayout override skipped reason=${e.javaClass.simpleName}"
            L.w { line }
            null
        }
    }

    private fun drop(current: Record, reason: String): String? {
        if (record == current) record = null
        val line = "[IndexActivity] staleLayout override skipped reason=$reason"
        L.w { line }
        return null
    }

    /**
     * The size and orientation of a window filling the panel: [widthPx] x [heightPx] in the
     * panel's natural orientation, turned by [rotation], at [densityDpi] (the density the
     * activity renders at, so dp x density is the real window). Every other field is left
     * undefined so locale, font scale, night mode and density pass through from the base.
     */
    fun sizeOverride(widthPx: Int, heightPx: Int, rotation: Int, densityDpi: Int): Configuration {
        val density = densityDpi / BASELINE_DPI
        val turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val wDp = ((if (turned) heightPx else widthPx) / density).toInt()
        val hDp = ((if (turned) widthPx else heightPx) / density).toInt()
        // Configuration() starts unset (fontScale 0, empty locales, undefined density), so only
        // the fields below are applied over the base.
        return Configuration().apply {
            screenWidthDp = wDp
            screenHeightDp = hDp
            smallestScreenWidthDp = minOf(wDp, hDp)
            orientation = if (wDp > hDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
    }

    private fun readAttachPanel(newBase: Context): OrientationPolicy.HostPanel? =
        OrientationPolicy.readHostPanel(newBase, ContextCompat.getDisplayOrDefault(newBase).displayId)

    private const val BASELINE_DPI = 160f

    @VisibleForTesting
    internal fun resetForTest() {
        record = null
    }
}
