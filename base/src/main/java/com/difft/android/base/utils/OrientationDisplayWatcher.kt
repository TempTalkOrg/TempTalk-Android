package com.difft.android.base.utils

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.difft.android.base.log.lumberjack.L

/**
 * Display-level replay trigger for [OrientationPolicy].
 *
 * A fold/unfold that the OS answers by LETTERBOXING the activity (HyperOS keeps a
 * portrait-requesting activity in a centered narrow window on the unfolded display) delivers
 * neither a relaunch nor `onConfigurationChanged`, so a folded-posture portrait request would
 * outlive the fold. A panel switch on the same display does not always relaunch either: HyperOS
 * has resized the window to the outer panel while keeping the activity at its inner-panel
 * configuration until the next unfold (#1454). `DisplayManager` reports the display change to
 * every process regardless of what the window manager has told this activity, which makes it
 * the one callback that is guaranteed to fire.
 *
 * Filters, in order:
 *  - the activity is still alive;
 *  - the activity is not pinned in picture-in-picture — a pinned window must never have its
 *    orientation re-requested; PiP exit delivers its own mode-change callback that re-applies;
 *  - the changed display IS the display hosting the activity. An external monitor (DeX,
 *    desktop mode, a dock) is a different display id and must not decide this activity's
 *    orientation. A foldable that moves the activity to another logical display always
 *    delivers that as a configuration change (the display id is part of it), which the
 *    caller's `onConfigurationChanged` replay already covers;
 *  - the display is on.
 *
 * Pair [start] with `onStart` and [stop] with `onStop`; [start] also replays the current host
 * display once, covering a posture change that happened while stopped. Shared by
 * [com.difft.android.base.BaseActivity] and the call activity, which is not a `BaseActivity`.
 */
class OrientationDisplayWatcher(
    private val activity: Activity,
    private val onPanelChanged: (Activity, Display, String, String) -> Unit = FoldDiagnostics::onPanelChanged,
    private val onHostDisplayChanged: (Display) -> Unit,
) {
    /** This instance's last host panel (`WxH`), for the #1454 diagnostics; null before the first delivery. */
    private var lastPanelKey: String? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = deliverIfHostDisplay(displayId)
    }

    /**
     * Register for display changes AND deliver the host display once right away. The listener is
     * only live between onStart and onStop, so a fold/unfold that happens while the activity is
     * stopped (home screen, another task) is never observed; if the OS then brings the activity
     * back letterboxed instead of relaunching it, this snapshot is the only replay it gets.
     * The consumer is idempotent, so the snapshot is silent when nothing changed.
     */
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

    /**
     * #1454 diagnostics, after the policy consumer ran: a panel change this instance sees (a change
     * event, or the onStart snapshot after a fold while stopped) starts a [FoldDiagnostics] session.
     * The first observation only records. Never lets a diagnostic failure reach the caller.
     */
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

    /**
     * Field diagnostic: one line per PANEL change process-wide (fold / unfold switch the mode
     * the host display reports), plus the first snapshot after process start as the positive
     * control. Together with the `orientation set` line and the activity relaunch lines this
     * fixes the order of a fold's display switch, policy replay and relaunch in a field log.
     * Keyed on the mode, not the activity, so a stack of activities starting does not repeat it.
     */
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

    // Application context on purpose: a Display fetched through an Activity context reports the
    // activity's window bounds from getRealMetrics (see OrientationPolicy.readShortSides).
    private fun displayManager(): DisplayManager? = activity.applicationContext.getSystemService<DisplayManager>()

    private companion object {
        /** Last panel signature logged, shared by every watcher in the process. */
        @Volatile
        private var lastLoggedPanel: String? = null
    }
}
