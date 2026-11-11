package com.difft.android.base.notification

import androidx.annotation.StringRes
import com.difft.android.base.R









object NotificationChannelIds {
    const val MESSAGE = "MESSAGE"
    const val CALL = "CALL"
    const val CRITICAL_ALERT = "CRITICAL_ALERT_V2"

    
    const val OTHER_ALERTS = "OTHER_ALERTS"

    
    const val RUNNING_IN_BACKGROUND = "RUNNING_IN_BACKGROUND"
    const val MESSAGE_GROUP = "MESSAGE_GROUP"

    
    val KEEP: List<String> = listOf(MESSAGE, CALL, CRITICAL_ALERT, OTHER_ALERTS, RUNNING_IN_BACKGROUND)

    
    const val OBSOLETE_MESSAGE_SUMMARY = "MESSAGE_SUMMARY"

    
    const val OBSOLETE_BACKGROUND = "BACKGROUND"

    



    val OBSOLETE: List<String> = listOf(
        OBSOLETE_MESSAGE_SUMMARY,
        "ONGOING_CALL",
        OBSOLETE_BACKGROUND,
        "ACCOUNT",
        "migration_p2p_transfer_progress",
        "migration_p2p_transfer_result",
        "app_upgrade",
        "CRITICAL_ALERT",
        "tt_livekit_foreground",
        "MESSAGES",
        "MESSAGE_NOTIFICATION_202X",
        "MESSAGE_AUTO_CANCEL_202X",
        "MESSAGE_AUTO_CANCEL",
        "EVENT",
        "ONLY_BADGE",
        "com.luck.picture.lib.com.luck.picture.lib.service.ForegroundService",
        "migration_backup_progress",
        "migration_backup_result",
    )

    
    @StringRes
    fun nameRes(channelId: String): Int? = when (channelId) {
        MESSAGE -> R.string.notification_channel_message
        CALL -> R.string.notification_channel_call
        CRITICAL_ALERT -> R.string.notification_channel_critical_alert
        OTHER_ALERTS -> R.string.notification_channel_other_alerts
        RUNNING_IN_BACKGROUND -> R.string.notification_channel_running_in_background
        MESSAGE_GROUP -> R.string.notification_channel_group_message
        else -> null
    }
}
