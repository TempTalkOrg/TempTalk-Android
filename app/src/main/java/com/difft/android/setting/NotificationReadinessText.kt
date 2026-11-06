package com.difft.android.setting

import android.content.Context
import com.difft.android.base.notification.CategoryLevel
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.call.R as CallR
import com.difft.android.chat.R as ChatR

/**
 * User-facing wording of the readiness model. Vendor-specific guidance lives on the setup-guide page.
 */
internal object NotificationReadinessText {

    /** Startup-dialog sentence for the only prompt-worthy issue (C2), or null for any other. */
    fun promptMessage(context: Context, issue: ReadinessIssue, appName: String): String? = when (issue) {
        ReadinessIssue.FULL_SCREEN_DENIED -> context.getString(CallR.string.notification_prompt_full_screen, appName)
        else -> null
    }

    /**
     * The status shown for a category row, or null for none: only a level the system reports is
     * shown; an unreadable level and one only vouched for by a notification test show nothing,
     * since a later change to an unreadable vendor switch would not invalidate the test.
     */
    fun categoryLevelLabel(context: Context, level: CategoryLevel): String? = when (level) {
        CategoryLevel.ON -> ChatR.string.notification_enable
        CategoryLevel.NO_BANNER -> ChatR.string.notification_level_no_banner
        CategoryLevel.LOCK_HIDDEN -> ChatR.string.notification_level_lock_hidden
        CategoryLevel.SILENT -> ChatR.string.notification_level_silent
        CategoryLevel.NEEDS_CHECK -> ChatR.string.notification_status_check
        CategoryLevel.OFF -> ChatR.string.notification_disable
        CategoryLevel.UNKNOWN, CategoryLevel.CONFIRMED, CategoryLevel.TESTED_OK -> null
    }?.let(context::getString)

    /** Levels that are a problem the user should fix. */
    fun isProblem(level: CategoryLevel): Boolean =
        level == CategoryLevel.OFF || level == CategoryLevel.NO_BANNER || level == CategoryLevel.LOCK_HIDDEN ||
            level == CategoryLevel.SILENT || level == CategoryLevel.NEEDS_CHECK

    /** Wording of a summary or dependency status; null shows nothing. */
    fun statusLabel(context: Context, label: StatusLabel?): String? = when (label) {
        StatusLabel.ON -> ChatR.string.notification_enable
        StatusLabel.OFF -> ChatR.string.notification_disable
        StatusLabel.NO_BANNER -> ChatR.string.notification_level_no_banner
        StatusLabel.LOCK_HIDDEN -> ChatR.string.notification_level_lock_hidden
        StatusLabel.SILENT -> ChatR.string.notification_level_silent
        StatusLabel.NEEDS_CHECK -> ChatR.string.notification_status_check
        StatusLabel.DND_BLOCKED -> ChatR.string.critical_alert_status_dnd_blocked
        StatusLabel.NEEDS_FULL_SCREEN -> ChatR.string.notification_status_needs_full_screen
        StatusLabel.NEEDS_CONNECTION -> ChatR.string.critical_alert_status_needs_connection
        null -> null
    }?.let(context::getString)

    /** Status wording of the full-screen row. */
    fun itemStatusLabel(context: Context, status: ReadinessStatus): String? = when (status) {
        ReadinessStatus.OK -> ChatR.string.notification_status_ok
        ReadinessStatus.PROBLEM -> ChatR.string.notification_disable
        ReadinessStatus.UNKNOWN -> null
    }?.let(context::getString)
}
