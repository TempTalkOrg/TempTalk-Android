package com.difft.android.chat.util

import android.content.Context
import android.os.PowerManager.WakeLock
import com.difft.android.base.log.lumberjack.L

object WakeLockUtil {

    /**
     * @param tag will be prefixed with "signal:" if it does not already start with it.
     */
    @JvmStatic
    fun acquire(context: Context, lockType: Int, timeout: Long, tag: String): WakeLock? {
        val prefixedTag = prefixTag(tag)
        return try {
            val powerManager = ServiceUtil.getPowerManager(context)
            val wakeLock = powerManager.newWakeLock(lockType, prefixedTag)
            wakeLock.acquire(timeout)
            wakeLock
        } catch (e: Exception) {
            L.w(e) { "Failed to acquire wakelock with tag: $prefixedTag" }
            null
        }
    }

    /**
     * @param tag will be prefixed with "signal:" if it does not already start with it.
     */
    @JvmStatic
    fun release(wakeLock: WakeLock?, tag: String) {
        val prefixedTag = prefixTag(tag)
        try {
            if (wakeLock == null) {
                L.d { "Wakelock was null. Skipping. Tag: $prefixedTag" }
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            } else {
                L.d { "Wakelock wasn't held at time of release: $prefixedTag" }
            }
        } catch (e: Exception) {
            L.w(e) { "Failed to release wakelock with tag: $prefixedTag" }
        }
    }

    private fun prefixTag(tag: String): String =
        if (tag.startsWith("signal:")) tag else "signal:$tag"
}
