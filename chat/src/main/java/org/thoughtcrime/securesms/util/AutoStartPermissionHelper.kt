package org.thoughtcrime.securesms.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import util.logging.Log

/**
 * 自启动权限辅助工具类
 * 用于检测设备是否支持自启动设置并引导用户开启自启动权限
 *
 * 支持的品牌：
 * - 中国品牌：小米、华为、荣耀、OPPO、一加、真我、vivo、iQOO、魅族、黑鲨、乐视
 * - 国际品牌：华硕、三星
 *
 * 通过PackageManager检测设备上是否真的有可用的自启动设置页面
 */
object AutoStartPermissionHelper {

    private val TAG = Log.tag(AutoStartPermissionHelper::class.java)

    /**
     * 常见的有自启动管理功能的手机品牌列表
     * 主要用于日志记录，不用于功能判断
     */
    private val BRANDS_WITH_AUTO_START = setOf(
        // 中国品牌
        "xiaomi", "redmi", "huawei", "honor", "oppo", "vivo",
        "realme", "oneplus", "meizu", "iqoo", "blackshark", "letv",
        // 国际品牌
        "asus", "samsung"
    )

    /**
     * 检测是否为有自启动管理功能的品牌手机（仅用于日志记录）
     */
    private fun hasAutoStartFeature(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return BRANDS_WITH_AUTO_START.any { it in brand || it in manufacturer }
    }

    /**
     * 获取当前设备对应的自启动Intent
     * 根据设备品牌智能匹配，避免无意义的尝试
     */
    private fun getAllAutoStartIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        // 根据品牌匹配对应的 Intent
        when {
            "xiaomi" in brand || "redmi" in brand || "xiaomi" in manufacturer -> {
                getXiaomiAutoStartIntent(context)?.let { intents.add(it) }
            }
            "huawei" in brand || "huawei" in manufacturer -> {
                getHuaweiAutoStartIntent(context)?.let { intents.add(it) }
            }
            "honor" in brand || "honor" in manufacturer -> {
                getHonorAutoStartIntent(context)?.let { intents.add(it) }
            }
            "oneplus" in brand || "oneplus" in manufacturer -> {
                // OnePlus 先尝试专用路径，失败则使用 OPPO 路径（OnePlus 基于 ColorOS）
                getOnePlusAutoStartIntent(context)?.let { intents.add(it) }
                getOppoAutoStartIntents(context).forEach { intents.add(it) }
            }
            "oppo" in brand || "realme" in brand || "oppo" in manufacturer || "realme" in manufacturer -> {
                getOppoAutoStartIntents(context).forEach { intents.add(it) }
            }
            "vivo" in brand || "iqoo" in brand || "vivo" in manufacturer || "iqoo" in manufacturer -> {
                getVivoAutoStartIntents(context).forEach { intents.add(it) }
            }
            "meizu" in brand || "meizu" in manufacturer -> {
                getMeizuAutoStartIntent(context)?.let { intents.add(it) }
            }
            "blackshark" in brand || "blackshark" in manufacturer -> {
                // 黑鲨手机基于 MIUI
                getXiaomiAutoStartIntent(context)?.let { intents.add(it) }
            }
            "letv" in brand || "letv" in manufacturer -> {
                getLetvAutoStartIntent(context)?.let { intents.add(it) }
            }
            "asus" in brand || "asus" in manufacturer -> {
                getAsusAutoStartIntent(context)?.let { intents.add(it) }
            }
            "samsung" in brand || "samsung" in manufacturer -> {
                getSamsungAutoStartIntent(context)?.let { intents.add(it) }
            }
            else -> {
                // 未知品牌，但在支持列表中，尝试所有可能的路径
                if (hasAutoStartFeature()) {
                    getXiaomiAutoStartIntent(context)?.let { intents.add(it) }
                    getHuaweiAutoStartIntent(context)?.let { intents.add(it) }
                    getHonorAutoStartIntent(context)?.let { intents.add(it) }
                    getOnePlusAutoStartIntent(context)?.let { intents.add(it) }
                    getOppoAutoStartIntents(context).forEach { intents.add(it) }
                    getVivoAutoStartIntents(context).forEach { intents.add(it) }
                    getMeizuAutoStartIntent(context)?.let { intents.add(it) }
                    getLetvAutoStartIntent(context)?.let { intents.add(it) }
                    getAsusAutoStartIntent(context)?.let { intents.add(it) }
                    getSamsungAutoStartIntent(context)?.let { intents.add(it) }
                }
            }
        }
        return intents
    }

    /**
     * 获取所有 vivo 自启动 Intent（返回列表）
     * 支持 vivo (FuntouchOS/ColorOS) 和 iQOO (OriginOS)
     */
    private fun getVivoAutoStartIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()

        val hasIqooSecure = isPackageExists(context, "com.iqoo.secure")
        val hasVivoPermManager = isPackageExists(context, "com.vivo.permissionmanager")
        val attempts = mutableListOf<ComponentName>()

        // iQOO/OriginOS 路径（优先，因为测试可用）
        if (hasIqooSecure) {
            attempts.add(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            attempts.add(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
            attempts.add(ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"))
        }

        // vivo/FuntouchOS 路径
        if (hasVivoPermManager) {
            attempts.add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            attempts.add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
        }

        for (component in attempts) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    putExtra("packagename", context.packageName)
                    putExtra("package_name", context.packageName) // 部分版本使用这个 key
                }
                intents.add(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create intent for ${component.className}", e)
            }
        }
        return intents
    }

    /**
     * 打开自启动设置页面
     * @return 是否成功打开设置页面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        return try {
            val allIntents = getAllAutoStartIntents(context)

            // 策略 1: 先尝试找到可以打开的 Intent（通过 queryIntentActivities）
            val verifiedIntent = allIntents.firstOrNull { intent ->
                try {
                    context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }

            if (verifiedIntent != null) {
                try {
                    verifiedIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(verifiedIntent)
                    return true
                } catch (e: SecurityException) {
                    // 权限不足，继续尝试其他方法
                } catch (e: ActivityNotFoundException) {
                    // Activity 不存在，继续尝试其他方法
                } catch (e: Exception) {
                    // 其他错误，继续尝试其他方法
                }
            }

            // 策略 2: 逐个尝试打开 Intent
            if (allIntents.isNotEmpty()) {
                for (intent in allIntents) {
                    try {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        return true
                    } catch (e: SecurityException) {
                        // 权限不足，跳过
                    } catch (e: ActivityNotFoundException) {
                        // Activity 不存在，跳过
                    } catch (e: Exception) {
                        // 其他错误，跳过
                    }
                }
            }

            // 策略 3: 降级方案 - 打开应用详情页面
            return openAppDetailsAsFallback(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open auto-start settings", e)
            false
        }
    }

    private fun getXiaomiAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
        } catch (e: Exception) {
            try {
                Intent().apply {
                    component = ComponentName(
                        "com.xiaomi.mipicks",
                        "com.xiaomi.mipicks.settings.AppPickerActivity"
                    )
                }
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun getHuaweiAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
        } catch (e: Exception) {
            try {
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                    )
                }
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun getHonorAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
        } catch (e: Exception) {
            try {
                getHuaweiAutoStartIntent(context)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun getOppoAutoStartIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val hasColorOSSafe = isPackageExists(context, "com.coloros.safecenter")
        val hasOppoSafe = isPackageExists(context, "com.oppo.safe")
        val attempts = mutableListOf<ComponentName>()

        if (hasColorOSSafe) {
            attempts.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.view.StartupAppListActivity"))
            attempts.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            attempts.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
            attempts.add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"))
        }

        if (hasOppoSafe) {
            attempts.add(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            attempts.add(ComponentName("com.oppo.safe", "com.oppo.safe.permission.PermissionTopActivity"))
        }

        for (component in attempts) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    putExtra("packageName", context.packageName)
                    putExtra("package_name", context.packageName)
                }
                intents.add(intent)
            } catch (e: Exception) {
                // Intent创建失败，跳过
            }
        }

        return intents
    }

    private fun getMeizuAutoStartIntent(context: Context): Intent? {
        return try {
            Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                putExtra("packageName", context.packageName)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 OnePlus 专用自启动 Intent
     * OnePlus 有自己的安全中心，优先使用
     */
    private fun getOnePlusAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 ASUS (华硕) 自启动 Intent
     * 包括 ROG 游戏手机
     */
    private fun getAsusAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.powersaver.PowerSaverSettings"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 Samsung (三星) 自启动 Intent
     * 通过电池优化设置
     */
    private fun getSamsungAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 Letv (乐视) 自启动 Intent
     */
    private fun getLetvAutoStartIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openAppDetailsAsFallback(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details page", e)
            false
        }
    }

    /**
     * 检查指定的包名是否存在
     */
    private fun isPackageExists(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun canOpenAutoStartSettings(context: Context): Boolean {
        return try {
            if (hasAutoStartFeature()) {
                return true
            }

            getAllAutoStartIntents(context).any { intent ->
                try {
                    context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
