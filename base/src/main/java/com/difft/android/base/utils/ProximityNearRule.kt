package com.difft.android.base.utils

import kotlin.math.min

/**
 * The single "phone is at the ear" rule for proximity-sensor readings, shared by the call screen
 * and voice-message playback.
 *
 * Mirrors AOSP `DisplayPowerProximityStateController` (threshold `min(maximumRange, 5cm)`), which
 * is also what Signal's `VoiceNoteProximityWakeLockManager` and Telegram's
 * `MediaController.isNearToSensor` do. Never compare against `maximumRange` alone: sensors whose
 * FAR reading sits below the advertised maximum (virtual ultrasonic sensors, some binary sensors)
 * would read NEAR forever under `value < maximumRange`.
 */
object ProximityNearRule {

    /** AOSP `DisplayPowerProximityStateController.TYPICAL_PROXIMITY_THRESHOLD`. */
    const val SYSTEM_NEAR_THRESHOLD_CM = 5.0f

    /**
     * @param distanceCm the sensor's `values[0]`
     * @param maximumRange the sensor's advertised `maximumRange`; a non-positive value (buggy HAL)
     * falls back to the system threshold alone.
     */
    fun isNear(distanceCm: Float, maximumRange: Float): Boolean {
        val threshold = if (maximumRange > 0f) min(maximumRange, SYSTEM_NEAR_THRESHOLD_CM) else SYSTEM_NEAR_THRESHOLD_CM
        return distanceCm >= 0f && distanceCm < threshold
    }
}
