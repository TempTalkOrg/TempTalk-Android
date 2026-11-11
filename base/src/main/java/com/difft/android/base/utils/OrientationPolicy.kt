package com.difft.android.base.utils

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch














object OrientationPolicy {

    
    const val LARGE_DISPLAY_MIN_SHORT_SIDE_DP = 600

    






















    fun applyTo(activity: Activity, display: Display? = null): Int {
        val hostDisplay = display ?: ContextCompat.getDisplayOrDefault(activity)
        val sides = readShortSides(activity, hostDisplay)
        val shortSideDp = sides.resolvedDp
        val target = targetForDisplayShortSide(shortSideDp)
        
        
        
        
        
        val pendingHere = isUnfoldPending(sides.panelKey)
        if (activity.requestedOrientation == target) return target
        
        
        
        
        
        
        if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && pendingHere) {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                L.i { "[BaseActivity] orientation held for unfold cls=${activity.javaClass.simpleName}" }
                try {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } catch (e: IllegalStateException) {
                    L.w { "[BaseActivity] orient hold skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
                }
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        
        
        
        
        
        L.i {
            "[BaseActivity] orientation set cls=${activity.javaClass.simpleName} " +
                "mfr=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} target=$target " +
                "displaySwDp=$shortSideDp modeSwDp=${sides.modeDp} boundsSwDp=${sides.boundsDp} " +
                "windowSwDp=${sides.windowDp}"
        }
        try {
            activity.requestedOrientation = target
        } catch (e: IllegalStateException) {
            
            
            
            L.w { "[BaseActivity] orient set skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
        }
        return target
    }

    










    fun preReleaseForUnfold(activity: Activity): Int? {
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) return null
        val sides = readShortSides(activity, ContextCompat.getDisplayOrDefault(activity))
        val panelDp = if (sides.modeDp > 0) sides.modeDp else sides.boundsDp
        if (panelDp >= LARGE_DISPLAY_MIN_SHORT_SIDE_DP) return null
        L.i {
            "[BaseActivity] orientation pre-release cls=${activity.javaClass.simpleName} " +
                "modeSwDp=${sides.modeDp} boundsSwDp=${sides.boundsDp} windowSwDp=${sides.windowDp}"
        }
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (e: IllegalStateException) {
            
            L.w { "[BaseActivity] orient pre-release skipped cls=${activity.javaClass.simpleName}: ${e.message}" }
            return null
        }
        
        
        if (sides.panelKey.isNotEmpty()) {
            pendingUnfoldPanel = sides.panelKey
            pendingUnfoldSince = SystemClock.uptimeMillis()
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    
    fun clearUnfoldPending() {
        pendingUnfoldPanel = null
    }

    




    fun isUnfoldPending(): Boolean =
        pendingUnfoldPanel != null && SystemClock.uptimeMillis() - pendingUnfoldSince <= UNFOLD_PENDING_MS

    
    const val UNFOLD_PENDING_MS = 1500L

    
    const val UNFOLD_RELOCK_SLACK_MS = 100L

    






    fun scheduleUnfoldRelock(owner: LifecycleOwner, reapply: () -> Unit) {
        owner.lifecycleScope.launch {
            delay(UNFOLD_PENDING_MS + UNFOLD_RELOCK_SLACK_MS)
            reapply()
        }
    }

    @Volatile
    private var pendingUnfoldPanel: String? = null

    @Volatile
    private var pendingUnfoldSince = 0L

    private fun isUnfoldPending(panelKey: String): Boolean {
        val panel = pendingUnfoldPanel ?: return false
        if (panel != panelKey || SystemClock.uptimeMillis() - pendingUnfoldSince > UNFOLD_PENDING_MS) {
            pendingUnfoldPanel = null
            return false
        }
        return true
    }

    
    fun targetForDisplayShortSide(shortSideDp: Int): Int =
        if (shortSideDp >= LARGE_DISPLAY_MIN_SHORT_SIDE_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    

















    data class ShortSides(val modeDp: Int, val boundsDp: Int, val windowDp: Int, val panelKey: String) {
        val resolvedDp: Int get() = resolveShortSideDp(modeDp, boundsDp, windowDp)
    }

    








    fun resolveShortSideDp(modeDp: Int, boundsDp: Int, windowDp: Int): Int = when {
        modeDp > 0 && windowDp - modeDp >= STALE_WINDOW_MARGIN_DP -> modeDp
        else -> maxOf(if (modeDp > 0) modeDp else boundsDp, windowDp)
    }

    




    const val STALE_WINDOW_MARGIN_DP = 32

    







    data class HostPanel(val key: String, val widthPx: Int, val heightPx: Int, val density: Float, val rotation: Int) {
        val shortPx: Int get() = minOf(widthPx, heightPx)
        val longPx: Int get() = maxOf(widthPx, heightPx)
        val shortDp: Int get() = dpOf(shortPx, density)
        val longDp: Int get() = dpOf(longPx, density)
        val widthDp: Int get() = dpOf(widthPx, density)
        val heightDp: Int get() = dpOf(heightPx, density)
    }

    





    @Suppress("DEPRECATION") 
    fun readHostPanel(context: Context, displayId: Int): HostPanel? {
        val display = context.applicationContext.getSystemService<DisplayManager>()?.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return hostPanelOf(display, metrics.density)
    }

    private fun hostPanelOf(display: Display, density: Float): HostPanel? {
        val mode = runCatching { display.mode }.getOrNull() ?: return null
        if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return null
        val rotation = runCatching { display.rotation }.getOrDefault(Surface.ROTATION_0)
        return HostPanel("${mode.physicalWidth}x${mode.physicalHeight}", mode.physicalWidth, mode.physicalHeight, density, rotation)
    }

    






    @Suppress("DEPRECATION") 
    fun readShortSides(activity: Activity, hostDisplay: Display): ShortSides {
        val display = activity.applicationContext.getSystemService<DisplayManager>()
            ?.getDisplay(hostDisplay.displayId) ?: hostDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val panel = hostPanelOf(display, metrics.density)
        return ShortSides(
            modeDp = panel?.shortDp ?: 0,
            boundsDp = dpOf(minOf(metrics.widthPixels, metrics.heightPixels), metrics.density),
            windowDp = activity.resources.configuration.smallestScreenWidthDp,
            panelKey = panel?.key.orEmpty(),
        )
    }

    private fun dpOf(px: Int, density: Float): Int = if (px > 0 && density > 0f) (px / density).toInt() else 0
}
