package com.difft.android.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.telephony.TelephonyManager

object NetUtil {
    //没有网络连接
    const val NETWORN_NONE = 0

    //wifi连接
    const val NETWORN_WIFI = 1

    //手机网络数据连接类型
    const val NETWORN_2G = 2
    const val NETWORN_3G = 3
    const val NETWORN_4G = 4
    const val NETWORN_5G = 5
    const val NETWORN_MOBILE = 6

    fun getNetWorkSumary(context: Context = com.difft.android.base.utils.application): String {
        return when (getNetworkState(context)) {
            NETWORN_NONE -> "none"
            NETWORN_WIFI -> "wifi"
            NETWORN_2G -> "2G"
            NETWORN_3G -> "3G"
            NETWORN_4G -> "4G"
            NETWORN_5G -> "5G"
            else -> "mobile"
        }
    }

    /**
     * 获取当前网络连接类型
     *
     * @param context
     * @return
     */
    private fun getNetworkState(context: Context): Int {
        //获取系统的网络服务
        val connManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        //如果当前没有网络
        //获取当前网络类型，如果为空，返回无网络
        @SuppressLint("MissingPermission") val activeNetInfo = connManager.activeNetworkInfo
        if (activeNetInfo == null || !activeNetInfo.isAvailable) {
            return NETWORN_NONE
        }
        // 判断是不是连接的是不是wifi
        @SuppressLint("MissingPermission") val wifiInfo =
            connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
        if (null != wifiInfo) {
            val state = wifiInfo.state
            if (null != state) if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING) {
                return NETWORN_WIFI
            }
        }
        // 如果不是wifi，则判断当前连接的是运营商的哪种网络2g、3g、4g等
        @SuppressLint("MissingPermission") val networkInfo =
            connManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)
        if (null != networkInfo) {
            val state = networkInfo.state
            val strSubTypeName = networkInfo.subtypeName
            if (null != state) if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING) {
                return when (activeNetInfo.subtype) {
                    TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN -> NETWORN_2G
                    TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP -> NETWORN_3G
                    TelephonyManager.NETWORK_TYPE_LTE -> NETWORN_4G
                    TelephonyManager.NETWORK_TYPE_NR -> NETWORN_5G
                    else ->                             //中国移动 联通 电信 三种3G制式
                        if (strSubTypeName.equals(
                                "TD-SCDMA",
                                ignoreCase = true
                            ) || strSubTypeName.equals(
                                "WCDMA",
                                ignoreCase = true
                            ) || strSubTypeName.equals(
                                "CDMA2000",
                                ignoreCase = true
                            )
                        ) {
                            NETWORN_3G
                        } else {
                            NETWORN_MOBILE
                        }
                }
            }
        }
        return NETWORN_NONE
    }

    /**
     * 判定是否有网络
     *
     * @param context
     * @return
     */
    fun checkNet(context: Context): Boolean {
        // 获取手机所有连接管理对象（包括对wi-fi,net等连接的管理）
        return try {
            val connectivity = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 获取网络连接管理的对象
            val info = connectivity.activeNetworkInfo
            // 判断当前网络是否已经连接
            info != null && info.isConnected
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 判定移动网络是否开启
     *
     * @param context
     * @return
     */
    fun isOpen3G(context: Context): Boolean {
        try {
            val connectivity = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 获取网络连接管理的对象
            val info = connectivity.activeNetworkInfo
            if (info != null && info.isConnected) {
                // 判断当前网络是否已经连接
                if (info.state == NetworkInfo.State.CONNECTED
                    && info.type == ConnectivityManager.TYPE_MOBILE
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    /**
     * 判定WIFI网络是否开启
     *
     * @param context
     * @return
     */
    fun isOpenWifi(context: Context): Boolean {
        try {
            val connectivity = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 获取网络连接管理的对象
            val info = connectivity.activeNetworkInfo
            if (info != null && info.isConnected) {
                // 判断当前网络是否已经连接
                if (info.state == NetworkInfo.State.CONNECTED
                    && info.type == ConnectivityManager.TYPE_WIFI
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }
}