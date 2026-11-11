package com.difft.android.base.utils

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.difft.android.base.log.lumberjack.L




























class OrientationDisplayWatcher(
    private val activity: Activity,
    private val onPanelChanged: (Activity, Display, String, String) -> Unit = FoldDiagnostics::onPanelChanged,
    private val onHostDisplayChanged: (Display) -> Unit,
) {
    
    private var lastPanelKey: String? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = deliverIfHostDisplay(displayId)
    }

    






    fun start() {
        displayManager()?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        deliverIfHostDisplay(ContextCompat.getDisplayOrDefault(activity).displayId, event = "snapshot")
    }

    private fun deliverIfHostDisplay(displayId: Int, event: String = "changed") {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.isInPictureInPictureMode) return
        if (displayId != ContextCompat.getDisplayOrDefault(activity).displayId) return
        val display = displayManager()?.getDisplay(displayId) ?: return
        if (display.state != Display.STATE_ON) return
        logPanelIfChanged(display, event)
        onHostDisplayChanged(display)
        diagnosePanelChange(display)
    }

    




    private fun diagnosePanelChange(display: Display) {
        try {
            val mode = runCatching { display.mode }.getOrNull()
            val key = "${mode?.physicalWidth}x${mode?.physicalHeight}"
            val from = lastPanelKey
            lastPanelKey = key
            if (from != null && key != from) onPanelChanged(activity, display, from, key)
        } catch (e: Exception) {
            val line = "[Fold] diag trigger failed cls=${activity.javaClass.simpleName} error=$e"
            L.w { line }
        }
    }

    






    private fun logPanelIfChanged(display: Display, event: String) {
        val mode = runCatching { display.mode }.getOrNull()
        val panel = "id=${display.displayId} mode=${mode?.physicalWidth}x${mode?.physicalHeight}"
        if (panel == lastLoggedPanel) return
        lastLoggedPanel = panel
        L.i { "[Display] host panel $event $panel cls=${activity.javaClass.simpleName}" }
    }

    fun stop() {
        displayManager()?.unregisterDisplayListener(listener)
    }

    
    
    private fun displayManager(): DisplayManager? = activity.applicationContext.getSystemService<DisplayManager>()

    private companion object {
        
        @Volatile
        private var lastLoggedPanel: String? = null
    }
}
