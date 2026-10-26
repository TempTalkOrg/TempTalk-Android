package com.difft.android.base.notification

import androidx.annotation.StringRes
import com.difft.android.base.R

/**
 * Ids of the app's own notification channels and the group, plus the localized display names.
 *
 * The id is the stable key the system and the user's settings are bound to and must never change;
 * only the display name is user-facing, and it is refreshed in place at startup (see
 * `NotificationChannelManager`). Every post uses a [KEEP] id; [OBSOLETE] ids exist only to be
 * deleted, and this file is the only place their literals may appear (#1354).
 */
object NotificationChannelIds {
    const val MESSAGE = "MESSAGE"
    const val CALL = "CALL"
    const val CRITICAL_ALERT = "CRITICAL_ALERT_V2"

    /** Logged-out notice, transfer result, sideload upgrade complete/failed. */
    const val OTHER_ALERTS = "OTHER_ALERTS"

    /** Every foreground-service notification: background connection, ongoing call, transfer, upgrade progress. */
    const val RUNNING_IN_BACKGROUND = "RUNNING_IN_BACKGROUND"
    const val MESSAGE_GROUP = "MESSAGE_GROUP"

    /** The channels the app creates and posts to; per-conversation `MESSAGE<conversationId>` channels are kept too. */
    val KEEP: List<String> = listOf(MESSAGE, CALL, CRITICAL_ALERT, OTHER_ALERTS, RUNNING_IN_BACKGROUND)

    /** Retired summary channel. Below API 31, deleting it while its summary is shown cancels the whole group. */
    const val OBSOLETE_MESSAGE_SUMMARY = "MESSAGE_SUMMARY"

    /** Retired background-connection channel; whether the user had blocked it is logged before the delete. */
    const val OBSOLETE_BACKGROUND = "BACKGROUND"

    /**
     * App-owned ids deleted at startup: the channels merged by #1354 and every id an earlier build
     * (including the difft-android `temp-talk` line) created. Only listed ids are ever deleted; a
     * third-party library's channel may back that library's own foreground service.
     */
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

    /** Localized display-name resource for a channel id, or null for ids the app does not name. */
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
