package com.difft.android.chat.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.utils.PackageUtil






internal fun buildMessageSummaryNotification(context: Context, groupKey: String): Notification =
    NotificationCompat.Builder(context, NotificationChannelIds.MESSAGE)
        .setContentTitle(PackageUtil.getAppName())
        .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
        .setGroup(groupKey)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setOnlyAlertOnce(true)
        .build()
