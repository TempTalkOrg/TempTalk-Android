package com.difft.android.call.util

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.application
import com.difft.android.call.R
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.lang.reflect.Method
import java.util.Locale


object FullScreenPermissionHelper {

    private const val MANUFACTURER_HUAWEI = "huawei"
    private const val MANUFACTURER_XIAOMI = "xiaomi"
    private const val MANUFACTURER_HONOR = "honor"
    private const val MANUFACTURER_OPPO = "oppo"
    private const val MANUFACTURER_ONEPLUS = "oneplus"
    private const val MANUFACTURER_REALME = "realme"

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        fun msgNotificationUtil(): IMessageNotificationUtil
    }

    private val msgNotificationUtil: IMessageNotificationUtil by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).msgNotificationUtil()
    }

    private val manufacturer: String
        get() = Build.MANUFACTURER.lowercase(Locale.ROOT)

    private fun isManufacturer(target: String) = manufacturer == target

    private fun getSystemProperty(prop: String): String {
        if (!prop.matches(Regex("^[a-zA-Z0-9._-]+$"))) return ""
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            get.invoke(null, prop) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun isMainStreamChinaMobile(): Boolean =
        manufacturer in setOf(MANUFACTURER_HUAWEI, MANUFACTURER_XIAOMI, MANUFACTURER_HONOR, MANUFACTURER_OPPO, MANUFACTURER_ONEPLUS, MANUFACTURER_REALME)

    fun isOppoEcosystemDevice(): Boolean {
        return manufacturer in setOf(MANUFACTURER_OPPO, MANUFACTURER_ONEPLUS, MANUFACTURER_REALME)
    }

    /**
     * Main entry for background popup or lockscreen popup behavior
     */
    fun canBackgroundStart(context: Context): Boolean = when (manufacturer) {
        MANUFACTURER_HUAWEI -> isHuaweiBgStartAllowed(context)
        MANUFACTURER_XIAOMI -> isXiaomiBgStartAllowed(context)
        else -> canShowOnLockScreen(context, msgNotificationUtil.getNotificationChannelName())
    }

    private fun isHuaweiBgStartAllowed(context: Context): Boolean = try {
        val clazz = Class.forName("com.huawei.android.app.AppOpsManagerEx")
        val checkMethod = clazz.getDeclaredMethod(
            "checkHwOpNoThrow",
            AppOpsManager::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        val opField = clazz.getDeclaredField("HW_OP_CODE_POPUP_BACKGROUND_WINDOW")
        val opCode = opField.getInt(null)

        val result = checkMethod.invoke(
            null,
            context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager,
            opCode,
            Process.myUid(),
            context.packageName
        ) as Int
        result == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        L.e { "[Call] Huawei BgPopup check failed: ${e.message}" }
        true
    }

    private fun isXiaomiBgStartAllowed(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val method: Method = ops.javaClass.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        val result = method.invoke(ops, 10021, Process.myUid(), context.packageName) as Int
        result == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        L.e { "[Call] Xiaomi BgPopup check failed: ${e.message}" }
        true
    }

    fun getAppPermissionsSettingIntent(context: Context): Intent = when (manufacturer) {
        MANUFACTURER_XIAOMI -> buildXiaomiPermissionIntent(context.packageName)
        MANUFACTURER_HONOR -> buildChannelSettingIntent(context, msgNotificationUtil.getNotificationChannelName())
        MANUFACTURER_OPPO, MANUFACTURER_ONEPLUS, MANUFACTURER_REALME -> buildAppNotificationIntent(context)
        else -> buildAppDetailIntent(context.packageName)
    }

    /** Xiaomi Permissions */
    private fun buildXiaomiPermissionIntent(packageName: String): Intent {
        val intent = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
        val version = getSystemProperty("ro.miui.ui.version.name")?.trimStart('V')?.toIntOrNull() ?: return intent

        val className = if (version >= 8)
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        else
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity"

        intent.setClassName("com.miui.securitycenter", className)
        return intent
    }

    private fun buildAppDetailIntent(packageName: String): Intent = Intent().apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        data = Uri.fromParts("package", packageName, null)
    }

    private fun buildChannelSettingIntent(context: Context, channelId: String): Intent = Intent().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            action = Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            action = "android.settings.APP_NOTIFICATION_SETTINGS"
            putExtra("app_package", context.packageName)
            putExtra("app_uid", context.applicationInfo.uid)
        }
    }

    private fun buildAppNotificationIntent(context: Context): Intent = Intent().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            action = "android.settings.APP_NOTIFICATION_SETTINGS"
            putExtra("app_package", context.packageName)
            putExtra("app_uid", context.applicationInfo.uid)
        }
    }

    fun jumpToPermissionSettingActivity(context: Context?) {
        try {
            context?.startActivity(getAppPermissionsSettingIntent(application))
        } catch (e: Exception) {
            L.e { "[Call] jumpToPermissionSettingActivity error: ${e.message}" }
            context?.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun getFullScreenSettingTip(): String = when {
        !isMainStreamChinaMobile() -> ResUtils.getString(R.string.fullscreen_notification_settings_oversea_tip)
        manufacturer == MANUFACTURER_HUAWEI -> ResUtils.getString(R.string.fullscreen_notification_settings_huawei_tip)
        manufacturer == MANUFACTURER_XIAOMI -> ResUtils.getString(R.string.fullscreen_notification_settings_xiaomi_tip)
        else -> ResUtils.getString(R.string.fullscreen_notification_settings_mainland_default_tip)
    }

    fun getNoPermissionTip(): String = when {
        !isMainStreamChinaMobile() -> ResUtils.getString(
            R.string.fullscreen_notification_no_permission_oversea_tip,
            PackageUtil.getAppName()
        )
        manufacturer == MANUFACTURER_HUAWEI -> ResUtils.getString(
            R.string.fullscreen_notification_no_permission_huawei_tip,
            PackageUtil.getAppName()
        )
        manufacturer == MANUFACTURER_XIAOMI -> ResUtils.getString(
            R.string.fullscreen_notification_no_permission_xiaomi_tip,
            PackageUtil.getAppName()
        )
        else -> ResUtils.getString(
            R.string.fullscreen_notification_no_permission_china_default_tip,
            PackageUtil.getAppName()
        )
    }

    fun canShowOnLockScreen(context: Context, channelId: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 1. 应用通知总开关
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        // 2. Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) return false
        }
        // 3. DND（勿扰模式）完全静音
        //    如果处于 INTERRUPTION_FILTER_NONE，则任何锁屏通知都可能被拦截
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            return false
        }
        // 4. Channel 权限（横幅通知 + 锁屏可见性）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(channelId)
            if (channel != null) {
                // 横幅通知需要 IMPORTANCE_HIGH 或以上
                if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                    return false
                }
                // 锁屏可见性不能是 SECRET，否则锁屏上不显示内容
                if (channel.lockscreenVisibility == Notification.VISIBILITY_SECRET) {
                    return false
                }
            }
        }
        return true
    }
}