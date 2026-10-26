package com.difft.android.base.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.net.toUri

/** The place a user has to go to fix a readiness issue. */
enum class NotificationFix {
    APP_NOTIFICATIONS,
    MESSAGE_CATEGORY,
    CALL_CATEGORY,
    CRITICAL_ALERT_CATEGORY,
    FULL_SCREEN_INTENT,
    OEM_POPUP,

    /** The app's own Do Not Disturb access page, for granting access. */
    DND_ACCESS,

    /** The Do Not Disturb access list, for reviewing or revoking an already granted access. */
    DND_ACCESS_LIST,

    /** The app's battery-usage page with the "Allow background usage" switch. */
    BACKGROUND_RESTRICTION,
}

/**
 * Jump chains for each fix: the most specific settings page first, then progressively more generic
 * ones, ending at the app-details page. Every step is try/caught, so an OEM page that is missing,
 * not exported or renamed degrades to the next step instead of crashing.
 */
object NotificationFixJump {

    private const val TAG = "NotificationFixJump"
    private const val BATTERY_USAGE_DETAIL_ACTION = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"
    private const val DND_ACCESS_DETAIL_ACTION = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

    fun fixFor(issue: ReadinessIssue): NotificationFix = when (issue) {
        ReadinessIssue.APP_NOTIFICATIONS_OFF -> NotificationFix.APP_NOTIFICATIONS
        ReadinessIssue.MESSAGE_CATEGORY_OFF,
        ReadinessIssue.MESSAGE_CATEGORY_SILENT,
        ReadinessIssue.MESSAGE_CATEGORY_NO_BANNER,
        ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN -> NotificationFix.MESSAGE_CATEGORY
        ReadinessIssue.CALL_CATEGORY_OFF,
        ReadinessIssue.CALL_CATEGORY_NO_BANNER,
        ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN -> NotificationFix.CALL_CATEGORY
        ReadinessIssue.CRITICAL_CATEGORY_OFF, ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE -> NotificationFix.CRITICAL_ALERT_CATEGORY
        ReadinessIssue.FULL_SCREEN_DENIED -> NotificationFix.FULL_SCREEN_INTENT
        ReadinessIssue.OEM_POPUP_CHECK -> NotificationFix.OEM_POPUP
    }

    /**
     * Opens the first page of [fix]'s chain that resolves. [messageGroupBlocked] comes from
     * [isMessageGroupBlocked], read off the main thread by the caller; it only matters for
     * [NotificationFix.MESSAGE_CATEGORY].
     */
    fun open(context: Context, fix: NotificationFix, messageGroupBlocked: Boolean = false): Boolean =
        OemSettingsLauncher.openInOrder(
            context,
            chain(context, fix, OemBrand.current(), messageGroupBlocked = messageGroupBlocked),
            TAG,
            fix = fix.name,
        )

    /**
     * Same walk as [open] for a launcher that reports back: [start] is the caller's launch (for
     * example an activity-result launcher), so a failing step still falls through to the next.
     */
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

    /**
     * The MESSAGE category page, or (API 31+) the page of one conversation inside it, with the same
     * fallbacks as every other jump. Callers handle a false return.
     */
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

    /**
     * [messageGroupBlocked]: the MESSAGE channel group is switched off. Its switch lives on the app's
     * notification page, while the channel page only shows the channel's own (still on) switch, so
     * the app page goes first.
     */
    fun chain(
        context: Context,
        fix: NotificationFix,
        brand: OemBrand,
        sdkInt: Int = Build.VERSION.SDK_INT,
        messageGroupBlocked: Boolean = false,
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
            NotificationFix.OEM_POPUP -> oemPopup(pkg, brand) + details
            // Hidden but exported Settings action (API 31+); older releases and failures land on app details.
            NotificationFix.BACKGROUND_RESTRICTION -> buildList {
                if (sdkInt >= Build.VERSION_CODES.S) add(Intent(BATTERY_USAGE_DETAIL_ACTION).setData("package:$pkg".toUri()))
                add(details)
            }
            NotificationFix.DND_ACCESS_LIST -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), details)
            NotificationFix.DND_ACCESS -> buildList {
                // Hidden action (not in the SDK): lands on this app's own switch. Huawei and Honor reject it.
                if (sdkInt >= Build.VERSION_CODES.Q && brand != OemBrand.HUAWEI && brand != OemBrand.HONOR) {
                    add(Intent(DND_ACCESS_DETAIL_ACTION).setData("package:$pkg".toUri()))
                }
                add(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                add(details)
            }
        }
    }

    /** Xiaomi is the only skin with a direct jump; every other vendor is sent to the setup guide instead. */
    private fun oemPopup(pkg: String, brand: OemBrand): List<Intent> =
        if (brand == OemBrand.XIAOMI) {
            listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setPackage("com.miui.securitycenter")
                    .putExtra("extra_pkgname", pkg)
            )
        } else {
            emptyList()
        }

    /** Whether the MESSAGE channel group is switched off. A binder call: never on the main thread. */
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
