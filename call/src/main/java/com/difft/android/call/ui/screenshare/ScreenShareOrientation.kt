package com.difft.android.call.ui.screenshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Display
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy

/**
 * Orientation while a remote screen share is on screen. A phone-sized display turns landscape and
 * follows the sensor between the two landscapes (both are allowed, as on iOS); a large display
 * keeps [OrientationPolicy]'s free rotation. A phone-sized display with a [halfOpen] fold is a flip
 * phone standing on its lower half: it keeps the policy's portrait lock, the share above the fold,
 * and a share already turned landscape turns back upright (design decision 38). Re-run on every
 * display/configuration change and on every half-open change, so a fold or unfold mid-share moves
 * between them.
 *
 * Returns the value [OrientationPolicy] wrote (the caller's ownership token), or null while the
 * share's own landscape lock holds the orientation.
 */
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
            // Same API 26 translucent-activity guard as OrientationPolicy.applyTo.
            L.w { "[Call] screen share orientation set skipped: ${e.message}" }
        }
    }
    return null
}
