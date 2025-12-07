package com.difft.android.call

import com.difft.android.base.utils.SharedPrefsUtil

object CallFeedbackTriggerManager {

    private const val RESET_INTERVAL_MS = 24 * 60 * 60 * 1000L //  24 hours in milliseconds
    private const val KEY_CALL_LAST_FEEDBACK_RESET_TIME = "call_last_feedback_reset_time"
    private const val KEY_CALL_FEEDBACK_RANDOM_THRESHOLD = "call_feedback_random_threshold"
    private const val KEY_CALL_FEEDBACK_HAS_TRIGGERED = "call_feedback_has_triggered"
    private const val KEY_CALL_COUNT = "call_count"

    /**
     * Check whether the 24-hour period has been exceeded, and if so, reset the data (generate a new threshold and clear the count)
     */
    @Synchronized
    private fun ensure24HourReset() {
        val lastReset = SharedPrefsUtil.getLong(KEY_CALL_LAST_FEEDBACK_RESET_TIME)
        val now = System.currentTimeMillis()

        if (now - lastReset >= RESET_INTERVAL_MS || lastReset == 0L) {
            val newThreshold = (1..5).random()
            SharedPrefsUtil.putLong(KEY_CALL_LAST_FEEDBACK_RESET_TIME, now)
            SharedPrefsUtil.putInt(KEY_CALL_FEEDBACK_RANDOM_THRESHOLD, newThreshold)
            SharedPrefsUtil.putBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, false)
            SharedPrefsUtil.putInt(KEY_CALL_COUNT, 0)
        }
    }

    /**
     * Called at the end of each call, automatically increments the call count and checks if feedback should be triggered
     */
    @Synchronized
    fun shouldTriggerFeedback(isForce: Boolean): Boolean {
        ensure24HourReset()

        val hasTriggered = SharedPrefsUtil.getBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, false)
        if (hasTriggered) return false

        val currentCount = SharedPrefsUtil.getInt(KEY_CALL_COUNT, 0) + 1
        val threshold = SharedPrefsUtil.getInt(KEY_CALL_FEEDBACK_RANDOM_THRESHOLD, 3)

        SharedPrefsUtil.putInt(KEY_CALL_COUNT, currentCount)

        if ((currentCount >= threshold) || isForce) {
            SharedPrefsUtil.putBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, true)
            return true
        }

        return false
    }

}