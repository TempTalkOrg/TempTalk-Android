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

    fun categoryLevelLabel(context: Context, level: CategoryLevel): String = context.getString(
        when (level) {
            CategoryLevel.ON -> ChatR.string.notification_enable
            CategoryLevel.NO_BANNER -> ChatR.string.notification_level_no_banner
            CategoryLevel.LOCK_HIDDEN -> ChatR.string.notification_level_lock_hidden
            CategoryLevel.SILENT -> ChatR.string.notification_level_silent
            CategoryLevel.OFF -> ChatR.string.notification_disable
            CategoryLevel.UNKNOWN -> ChatR.string.notification_level_check
        }
    )

    /** Status wording of the call and lock-screen rows. */
    fun itemStatusLabel(context: Context, status: ReadinessStatus): String = context.getString(
        when (status) {
            ReadinessStatus.OK -> ChatR.string.notification_status_ok
            ReadinessStatus.UNKNOWN -> ChatR.string.notification_status_check
            ReadinessStatus.PROBLEM -> ChatR.string.notification_status_problem
        }
    )
}
