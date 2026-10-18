package com.difft.android.base.notification

import androidx.annotation.StringRes
import com.difft.android.base.R

/**
 * Ids of the app's own notification channels and the group, plus the localized display names.
 *
 * The id is the stable key the system and the user's settings are bound to and must never change;
 * only the display name is user-facing, and it is refreshed in place at startup (see
 * `NotificationChannelManager`).
 */
object NotificationChannelIds {
    const val MESSAGE = "MESSAGE"
    const val MESSAGE_SUMMARY = "MESSAGE_SUMMARY"
    const val CALL = "CALL"
    const val ONGOING_CALL = "ONGOING_CALL"
    const val BACKGROUND = "BACKGROUND"
    const val CRITICAL_ALERT = "CRITICAL_ALERT_V2"
    const val ACCOUNT = "ACCOUNT"

    /** Pre-V2 critical-alert channel id; the app no longer posts to it. */
    const val LEGACY_CRITICAL_ALERT = "CRITICAL_ALERT"
    const val MESSAGE_GROUP = "MESSAGE_GROUP"

    /** Localized display-name resource for a channel id, or null for ids the app does not name. */
    @StringRes
    fun nameRes(channelId: String): Int? = when (channelId) {
        MESSAGE -> R.string.notification_channel_message
        MESSAGE_SUMMARY -> R.string.notification_channel_message_summary
        CALL -> R.string.notification_channel_call
        ONGOING_CALL -> R.string.notification_channel_ongoing_call
        BACKGROUND -> R.string.notification_channel_background
        CRITICAL_ALERT -> R.string.notification_channel_critical_alert
        ACCOUNT -> R.string.notification_channel_account
        MESSAGE_GROUP -> R.string.notification_channel_group_message
        else -> null
    }
}
