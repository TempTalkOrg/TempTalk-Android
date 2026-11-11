package com.difft.android.base.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.difft.android.base.log.lumberjack.L

object PackageUtil {

    fun getPackageName(): String {
        val context: Context = application
        return context.packageName
    }

    fun getAppName(): String? {
        try {
            val context = application
            val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            return context.packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            L.w { "[PackageUtil] error: ${e.stackTraceToString()}" }
        }
        return null
    }

    fun getAppVersionCode(): Int {
        val packageInfo = getPackageInfo()
        return packageInfo?.versionCode ?: 0
    }

    fun getAppVersionName(): String? {
        val packageInfo = getPackageInfo()
        return if (packageInfo == null) "" else packageInfo.versionName
    }

    private fun getPackageInfo(): PackageInfo? {
        return try {
            val context: Context = com.difft.android.base.utils.application
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            L.w { "[PackageUtil] error: ${e.stackTraceToString()}" }
            null
        }
    }

    


    fun isAppInstalled(packageName: String): Boolean {
        val pm = com.difft.android.base.utils.application.packageManager
        return try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }


}
