package com.difft.android.base.notification

import android.app.NotificationManager
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.net.toUri


enum class NotificationFix {
    APP_NOTIFICATIONS,
    MESSAGE_CATEGORY,
    CALL_CATEGORY,
    CRITICAL_ALERT_CATEGORY,
    FULL_SCREEN_INTENT,
    OEM_POPUP,

    
    DND_ACCESS,

    
    DND_ACCESS_LIST,

    
    BACKGROUND_RESTRICTION,
}






object NotificationFixJump {

    private const val TAG = "NotificationFixJump"
    private const val BATTERY_USAGE_DETAIL_ACTION = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"
    private const val DND_ACCESS_DETAIL_ACTION = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

    fun fixFor(issue: ReadinessIssue): NotificationFix = when (issue) {
        ReadinessIssue.APP_NOTIFICATIONS_OFF -> NotificationFix.APP_NOTIFICATIONS
        ReadinessIssue.MESSAGE_CATEGORY_OFF,
        ReadinessIssue.MESSAGE_CATEGORY_SILENT,
        ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER,
        ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN,
        ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET -> NotificationFix.MESSAGE_CATEGORY
        ReadinessIssue.CALL_CATEGORY_OFF,
        ReadinessIssue.CALL_CATEGORY_NO_BANNER,
        ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN,
        ReadinessIssue.CALL_CATEGORY_NEVER_SET -> NotificationFix.CALL_CATEGORY
        ReadinessIssue.CRITICAL_CATEGORY_OFF,
        ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE,
        ReadinessIssue.CRITICAL_CATEGORY_LOCK_HIDDEN,
        ReadinessIssue.CRITICAL_CATEGORY_NEVER_SET -> NotificationFix.CRITICAL_ALERT_CATEGORY
        ReadinessIssue.FULL_SCREEN_DENIED -> NotificationFix.FULL_SCREEN_INTENT
        ReadinessIssue.OEM_POPUP_CHECK -> NotificationFix.OEM_POPUP
    }

    




    fun seenCategory(fix: NotificationFix, opened: Intent?): String? {
        if (opened?.action != Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) return null
        val channelId = when (fix) {
            NotificationFix.MESSAGE_CATEGORY -> NotificationChannelIds.MESSAGE
            NotificationFix.CALL_CATEGORY -> NotificationChannelIds.CALL
            NotificationFix.CRITICAL_ALERT_CATEGORY -> NotificationChannelIds.CRITICAL_ALERT
            else -> return null
        }
        return channelId.takeIf { opened.getStringExtra(Settings.EXTRA_CHANNEL_ID) == it }
    }

    




    fun open(context: Context, fix: NotificationFix, messageGroupBlocked: Boolean = false): Boolean =
        openTracking(context, fix, messageGroupBlocked) != null

    
    fun openTracking(context: Context, fix: NotificationFix, messageGroupBlocked: Boolean = false): Intent? {
        var started: Intent? = null
        val opened = OemSettingsLauncher.startFirst(
            chain(context, fix, OemBrand.current(), messageGroupBlocked = messageGroupBlocked),
            TAG,
            fix = fix.name,
        ) { intent ->
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            started = intent
        }
        return if (opened) started else null
    }

    



    fun openForResult(
        context: Context,
        fix: NotificationFix,
        messageGroupBlocked: Boolean = false,
        start: (Intent) -> Unit,
    ): Boolean =
        OemSettingsLauncher.startFirst(
            chain(context, fix, OemBrand.current(), messageGroupBlocked = messageGroupBlocked),
            TAG,
            fix.name,
            start,
        )

    



    fun openMessageCategory(context: Context, conversationId: String? = null): Boolean =
        OemSettingsLauncher.openInOrder(context, messageCategoryChain(context, conversationId), TAG, fix = "MESSAGE_CATEGORY")

    fun messageCategoryChain(
        context: Context,
        conversationId: String?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<Intent> {
        val pkg = context.packageName
        val category = channel(pkg, NotificationChannelIds.MESSAGE).apply {
            if (conversationId != null && sdkInt >= Build.VERSION_CODES.S) {
                putExtra(Settings.EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        return listOf(category, appNotifications(pkg), OemSettingsLauncher.appDetailsIntent(context))
    }

    




    fun chain(
        context: Context,
        fix: NotificationFix,
        brand: OemBrand,
        sdkInt: Int = Build.VERSION.SDK_INT,
        messageGroupBlocked: Boolean = false,
        originOsMajor: Int? = if (brand == OemBrand.VIVO) VivoOs.currentOriginMajor() else null,
    ): List<Intent> {
        val pkg = context.packageName
        val details = OemSettingsLauncher.appDetailsIntent(context)
        return when (fix) {
            NotificationFix.APP_NOTIFICATIONS -> listOf(appNotifications(pkg), details)
            NotificationFix.MESSAGE_CATEGORY ->
                if (messageGroupBlocked) {
                    listOf(appNotifications(pkg), details)
                } else {
                    listOf(channel(pkg, NotificationChannelIds.MESSAGE), appNotifications(pkg), details)
                }
            NotificationFix.CALL_CATEGORY -> listOf(channel(pkg, NotificationChannelIds.CALL), appNotifications(pkg), details)
            NotificationFix.CRITICAL_ALERT_CATEGORY ->
                listOf(channel(pkg, NotificationChannelIds.CRITICAL_ALERT), appNotifications(pkg), details)
            NotificationFix.FULL_SCREEN_INTENT -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData("package:$pkg".toUri()),
                appNotifications(pkg),
                details,
            )
            NotificationFix.OEM_POPUP -> oemPopup(pkg, brand, originOsMajor) + details
            
            NotificationFix.BACKGROUND_RESTRICTION -> buildList {
                if (sdkInt >= Build.VERSION_CODES.S) add(Intent(BATTERY_USAGE_DETAIL_ACTION).setData("package:$pkg".toUri()))
                add(details)
            }
            NotificationFix.DND_ACCESS_LIST -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), details)
            NotificationFix.DND_ACCESS -> buildList {
                
                if (sdkInt >= Build.VERSION_CODES.Q && brand != OemBrand.HUAWEI && brand != OemBrand.HONOR) {
                    add(Intent(DND_ACCESS_DETAIL_ACTION).setData("package:$pkg".toUri()))
                }
                add(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                add(details)
            }
        }
    }

    



    private fun oemPopup(pkg: String, brand: OemBrand, originOsMajor: Int?): List<Intent> = when {
        brand == OemBrand.XIAOMI -> listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setPackage("com.miui.securitycenter")
                .putExtra("extra_pkgname", pkg)
        )
        brand == OemBrand.VIVO && VivoOs.supportsDirectPermissionPage(originOsMajor) -> listOf(VivoOs.permissionDetailIntent(pkg))
        else -> emptyList()
    }

    



    @WorkerThread
    fun opensOemPopupDirectly(
        context: Context,
        brand: OemBrand = OemBrand.current(),
        originOsMajor: Int? = if (brand == OemBrand.VIVO) VivoOs.currentOriginMajor() else null,
    ): Boolean = when (brand) {
        OemBrand.XIAOMI -> true
        OemBrand.VIVO -> VivoOs.supportsDirectPermissionPage(originOsMajor) &&
            OemSettingsLauncher.canResolveAny(context, oemPopup(context.packageName, brand, originOsMajor))
        else -> false
    }

    
    @WorkerThread
    fun isMessageGroupBlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannelGroup(NotificationChannelIds.MESSAGE_GROUP)?.isBlocked == true

    private fun appNotifications(pkg: String) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)

    private fun channel(pkg: String, channelId: String) =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
}
