package com.difft.android.chat.util

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.RequiresApi

/**
 * Easy access to various properties of the device, typically to make performance-related decisions.
 */
object DeviceProperties {

    @JvmStatic
    fun isLowMemoryDevice(context: Context): Boolean {
        val activityManager = ServiceUtil.getActivityManager(context)
        return activityManager.isLowRamDevice
    }

    @JvmStatic
    fun getMemoryClass(context: Context): Int {
        val activityManager = ServiceUtil.getActivityManager(context)
        return activityManager.memoryClass
    }

    @JvmStatic
    fun getMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        val activityManager = ServiceUtil.getActivityManager(context)
        activityManager.getMemoryInfo(info)
        return info
    }

    @JvmStatic
    @RequiresApi(28)
    fun isBackgroundRestricted(context: Context): Boolean {
        val activityManager = ServiceUtil.getActivityManager(context)
        return activityManager.isBackgroundRestricted
    }

    @JvmStatic
    fun getDataSaverState(context: Context): DataSaverState {
        return when (ServiceUtil.getConnectivityManager(context).restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> DataSaverState.ENABLED
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> DataSaverState.ENABLED_BUT_EXEMPTED
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> DataSaverState.DISABLED
            else -> DataSaverState.DISABLED
        }
    }

    enum class DataSaverState(val isEnabled: Boolean, val isRestricted: Boolean) {
        /** Data saver is enabled system-wide, and we are subject to the restrictions. */
        ENABLED(true, true),

        /** Data saver is enabled system-wide, but the user has exempted us by giving us 'unrestricted access' to data in the system settings */
        ENABLED_BUT_EXEMPTED(true, false),

        /** Data saver is disabled. */
        DISABLED(false, false)
    }
}
