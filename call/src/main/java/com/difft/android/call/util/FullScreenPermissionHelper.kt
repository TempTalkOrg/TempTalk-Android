package com.difft.android.call.util

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.application
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.Locale

object FullScreenPermissionHelper {

    const val MANUFACTURER_HUAWEI = "huawei" //华为
    const val MANUFACTURER_XIAOMI = "xiaomi" //小米
    const val MANUFACTURER_HONOR = "honor" //荣耀

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        fun msgNotificationUtil(): IMessageNotificationUtil
    }

    private val msgNotificationUtil: IMessageNotificationUtil by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).msgNotificationUtil()
    }

    private fun isHuawei(): Boolean {
        return checkManufacturer("huawei")
    }

    private fun isMiui(): Boolean {
        val value = getSystemProperty("ro.miui.ui.version.name")
        return !TextUtils.isEmpty(value)
    }

    private fun isXiaoMi(): Boolean {
        return checkManufacturer("xiaomi")
    }

    private fun isHonor(): Boolean {
        return checkManufacturer("honor")
    }

    private fun checkManufacturer(manufacturer: String): Boolean {
        return manufacturer.equals(Build.MANUFACTURER, true)
    }

    private fun getSystemProperty(propName: String): String? {
        // Validate input to prevent command injection
        if (!propName.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
            return null
        }

        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", propName))
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                reader.readLine()
            }
        } catch (ex: IOException) {
            null
        }
    }


    fun isMainStreamChinaMobile(): Boolean {
        return isHuawei() || isXiaoMi() || isHonor()
    }

    fun canBackgroundStart(context: Context): Boolean {
        if (isHuawei()) {
            return isHuaweiBgStartPermissionAllowed(context)
        }
        if (isXiaoMi() && isMiui()) {
            return isXiaomiBgStartPermissionAllowed(context)
        }
        if (isHonor()) {
            return isBannerNotificationEnabled(context, msgNotificationUtil.getNotificationChannelName())
        }
        return true
    }

    private fun isHuaweiBgStartPermissionAllowed(context: Context): Boolean {
        try {
            val appOpsManagerEx = Class.forName("com.huawei.android.app.AppOpsManagerEx")
            val checkHwOpNoThrow = appOpsManagerEx.getDeclaredMethod(
                "checkHwOpNoThrow",
                AppOpsManager::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            var op = 100000
            op = appOpsManagerEx.getDeclaredField("HW_OP_CODE_POPUP_BACKGROUND_WINDOW").getInt(op)
            val checkResult = checkHwOpNoThrow.invoke(
                appOpsManagerEx.newInstance(),
                context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager,
                op,
                Process.myUid(),
                context.packageName
            ) as Int
            return checkResult == AppOpsManager.MODE_ALLOWED

        } catch (e: Exception) {
            e.printStackTrace()
            L.e {"[Call] FullScreenPermissionHelper HuaWei check BackgroundPopup error"}
        }
        return true
    }

    private fun isXiaomiBgStartPermissionAllowed(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        try {
            val op = 10021
            val method: Method = ops.javaClass.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            val result = method.invoke(ops, op, Process.myUid(), context.packageName) as Int
            return result == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            e.printStackTrace()
            L.e {"[Call] FullScreenPermissionHelper Xiaomi check BackgroundPopup error"}
        }
        return true
    }

    fun getAppPermissionsSettingIntent(context: Context): Intent {
        return when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
//            MANUFACTURER_HUAWEI -> huawei(packageName)
            MANUFACTURER_XIAOMI -> xiaomi(context.packageName)
            MANUFACTURER_HONOR -> getAppNotificationSettingIntent(context, msgNotificationUtil.getNotificationChannelName())
            else -> getAppDetailSettingIntent(context.packageName)
        }
    }

    /**
     * 华为跳转权限设置页
     */
    private fun huawei(packageName: String): Intent {
        val intent = Intent()
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra("packageName", packageName)
        val comp = ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.permissionmanager.ui.MainActivity"
        )
        intent.component = comp
        return intent
    }

    /**
     * 小米跳转权限设置页
     */
    private fun xiaomi(packageName: String): Intent {
        val intent = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra(
            "extra_pkgname",
            packageName
        )
        getMIUIVersion()?.let {
            val versionCode = it.trimStart('V').toIntOrNull()
            versionCode?.let {
                return intent.setClassName(
                    "com.miui.securitycenter",
                    if (it >= 8)  "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    else "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
            }
        }
        return intent.setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        )
    }

    private fun getMIUIVersion(): String? {
        return getSystemProperty("ro.miui.ui.version.name")
    }

    /**
     * 获取应用详情页面
     */
    private fun getAppDetailSettingIntent(packageName: String): Intent {
        val localIntent = Intent()
        localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        localIntent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        localIntent.data = Uri.fromParts("package", packageName, null)
        return localIntent
    }


    /**
     * 获取应用通知Channel管理设置页面
     */
    private fun getAppNotificationSettingIntent(context: Context, channelId: String): Intent {
        val localIntent = Intent()
        // Android 8.0 (API 26) 及以上：跳转到应用通知Channel设置页
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            localIntent.action = Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            localIntent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            localIntent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            localIntent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
            localIntent.putExtra("app_package", context.packageName)
            localIntent.putExtra("app_uid", context.applicationInfo.uid)
        }
        return localIntent
    }

    /**
     * 跳转到权限设置页面
     */
    fun jumpToPermissionSettingActivity(context: Context?) {
        try {
            val intent = getAppPermissionsSettingIntent(application)
            context?.startActivity(intent)
        } catch (e: Exception) {
            L.e { "[Call] jumpToPermissionSettingActivity error: ${e.message}" }
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context?.startActivity(intent)
        }
    }

    /**
     * 检测横幅通知是否开启
     */
    fun isBannerNotificationEnabled(context: Context, channelId: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(channelId)
            if (channel != null) {
                // 在 Android 的通知机制中，只有 importance >= HIGH 才可能展示横幅（Heads-up / Banner）
                return channel.importance >= NotificationManager.IMPORTANCE_HIGH
            }
        }
        return true
    }


}