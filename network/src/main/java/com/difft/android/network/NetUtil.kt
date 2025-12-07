package com.difft.android.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.difft.android.base.utils.application

/**
 * Network utility class for checking network status and type
 */
object NetUtil {

    /**
     * Get network type summary string
     */
    fun getNetWorkSummary(context: Context = application): String {
        return getNetworkType(context).displayName
    }

    /**
     * Check if network is available
     */
    fun checkNet(context: Context = application): Boolean {
        return getNetworkType(context) != NetworkType.NONE
    }

    /**
     * Check if connected via mobile data
     */
    fun isMobileConnected(context: Context = application): Boolean {
        return getNetworkType(context).isMobile
    }

    /**
     * Check if connected via WiFi
     */
    fun isWifiConnected(context: Context = application): Boolean {
        return getNetworkType(context) == NetworkType.WIFI
    }

    /**
     * Get current network type
     */
    fun getNetworkType(context: Context = application): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE

        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getMobileNetworkType()
            else -> NetworkType.NONE
        }
    }

    /**
     * Get mobile network type (2G/3G/4G/5G)
     *
     * Android 12+ uses READ_BASIC_PHONE_STATE (normal permission, auto-granted)
     * Older versions may work without permission on some devices
     * Falls back to MOBILE if unable to determine
     */
    @SuppressLint("MissingPermission")
    private fun getMobileNetworkType(): NetworkType {
        val telephonyManager = application.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return NetworkType.MOBILE

        return try {
            getMobileNetworkSubtype(telephonyManager.dataNetworkType)
        } catch (_: SecurityException) {
            NetworkType.MOBILE
        }
    }

    @Suppress("DEPRECATION")
    private fun getMobileNetworkSubtype(subtype: Int): NetworkType {
        return when (subtype) {
            // 2G
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> NetworkType.MOBILE_2G

            // 3G
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> NetworkType.MOBILE_3G

            // 4G
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> NetworkType.MOBILE_4G

            // 5G
            TelephonyManager.NETWORK_TYPE_NR -> NetworkType.MOBILE_5G

            else -> NetworkType.MOBILE
        }
    }

    /**
     * Network type enum
     */
    enum class NetworkType(val displayName: String, val isMobile: Boolean = false) {
        NONE("none"),
        WIFI("wifi"),
        MOBILE_2G("2G", true),
        MOBILE_3G("3G", true),
        MOBILE_4G("4G", true),
        MOBILE_5G("5G", true),
        MOBILE("mobile", true)
    }
}