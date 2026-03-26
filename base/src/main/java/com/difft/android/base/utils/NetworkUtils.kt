package com.difft.android.base.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

object NetworkUtils {

    private val appContext: Context get() = ApplicationHelper.instance

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getNetworkSummary(context: Context = appContext): String {
        return getNetworkType(context).displayName
    }

    fun isMobileConnected(context: Context = appContext): Boolean {
        return getNetworkType(context).isMobile
    }

    fun isWifiConnected(context: Context = appContext): Boolean {
        return getNetworkType(context) == NetworkType.WIFI
    }

    fun getNetworkType(context: Context = appContext): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE

        val network = cm.activeNetwork ?: return NetworkType.NONE
        val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getMobileNetworkType()
            else -> NetworkType.NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun getMobileNetworkType(): NetworkType {
        val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
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
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> NetworkType.MOBILE_2G

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

            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> NetworkType.MOBILE_4G

            TelephonyManager.NETWORK_TYPE_NR -> NetworkType.MOBILE_5G

            else -> NetworkType.MOBILE
        }
    }

    enum class NetworkType(val displayName: String, val isMobile: Boolean = false) {
        NONE("none"),
        WIFI("wifi"),
        ETHERNET("ethernet"),
        MOBILE_2G("2G", true),
        MOBILE_3G("3G", true),
        MOBILE_4G("4G", true),
        MOBILE_5G("5G", true),
        MOBILE("mobile", true),
    }
}
