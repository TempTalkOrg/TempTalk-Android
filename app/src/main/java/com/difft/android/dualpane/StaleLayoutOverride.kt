package com.difft.android.dualpane

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.Surface
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy














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

    
    fun clearIfNotFor(panelKey: String) {
        if (record?.panelKey != panelKey) record = null
    }

    






    fun applyIfActive(
        activity: ContextThemeWrapper,
        newBase: Context,
        readPanel: (Context) -> OrientationPolicy.HostPanel? = ::readAttachPanel,
    ): String? {
        val current = record ?: return null
        return try {
            val panel = readPanel(newBase)
            
            
            
            val incoming = Configuration(activity.baseContext.resources.configuration).apply {
                densityDpi = newBase.resources.configuration.densityDpi
            }
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

    





    fun sizeOverride(widthPx: Int, heightPx: Int, rotation: Int, densityDpi: Int): Configuration {
        val density = densityDpi / BASELINE_DPI
        val turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val wDp = ((if (turned) heightPx else widthPx) / density).toInt()
        val hDp = ((if (turned) widthPx else heightPx) / density).toInt()
        
        
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
