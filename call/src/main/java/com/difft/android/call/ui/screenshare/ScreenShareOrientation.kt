package com.difft.android.call.ui.screenshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Display
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy













@SuppressLint("SourceLockedOrientationActivity")
internal fun applyScreenShareOrientation(activity: Activity, display: Display? = null, halfOpen: Boolean = false): Int? {
    val hostDisplay = display ?: ContextCompat.getDisplayOrDefault(activity)
    val sides = OrientationPolicy.readShortSides(activity, hostDisplay)
    val target = OrientationPolicy.targetForDisplayShortSide(sides.resolvedDp)
    if (target != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || halfOpen) {
        if (halfOpen && activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            L.i { "[Call] screen share orientation back to policy: fold half open displaySwDp=${sides.resolvedDp}" }
        }
        return OrientationPolicy.applyTo(activity, display)
    }
    if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
        L.i { "[Call] screen share orientation set sensorLandscape displaySwDp=${sides.resolvedDp}" }
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (e: IllegalStateException) {
            
            L.w { "[Call] screen share orientation set skipped: ${e.message}" }
        }
    }
    return null
}
