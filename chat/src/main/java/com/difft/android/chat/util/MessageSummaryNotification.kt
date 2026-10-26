package com.difft.android.chat.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.utils.PackageUtil

/**
 * The group summary of message notifications. It sits on the alerting MESSAGE channel, so
 * GROUP_ALERT_CHILDREN is what keeps it silent: the system suppresses sound, vibration and the
 * heads-up of a summary under that behavior, and only the children alert (#1354).
 */
internal fun buildMessageSummaryNotification(context: Context, groupKey: String): Notification =
    NotificationCompat.Builder(context, NotificationChannelIds.MESSAGE)
        .setContentTitle(PackageUtil.getAppName())
        .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
        .setGroup(groupKey)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setOnlyAlertOnce(true)
        .build()
