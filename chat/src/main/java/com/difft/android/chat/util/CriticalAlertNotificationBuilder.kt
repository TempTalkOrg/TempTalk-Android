package com.difft.android.chat.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.LCallConstants
import com.difft.android.chat.common.StopCriticalAlertSoundReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject


data class CriticalAlertPayload(
    val conversationId: String,
    val title: String,
    val content: String,
    val notificationId: Int,
    val timestamp: Long,
    val roomId: String?,
)

enum class CriticalAlertPresentation {
    
    FULL_SCREEN,

    



    SILENT_COMPANION,
}


class CriticalAlertNotificationBuilder @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val activityProvider: ActivityProvider,
) {

    fun build(payload: CriticalAlertPayload, channelId: String, presentation: CriticalAlertPresentation): Notification {
        val title = "🚨${payload.title}"

        
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(payload.content)
            .setBigContentTitle(title)

        
        
        val contentAction = when (presentation) {
            CriticalAlertPresentation.FULL_SCREEN -> LCallConstants.CRITICAL_ALERT_ACTION_CLICKED
            CriticalAlertPresentation.SILENT_COMPANION -> null
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(payload.content)
            .setContentIntent(createActivityIntent(payload, contentAction))
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setOnlyAlertOnce(false)
            .setStyle(bigTextStyle)
            .setWhen(payload.timestamp) 
            .setShowWhen(true)
            .setUsesChronometer(false)

        when (presentation) {
            CriticalAlertPresentation.FULL_SCREEN -> builder
                .setFullScreenIntent(createActivityIntent(payload, action = null), true)
                .setDeleteIntent(createStopSoundIntent(payload.notificationId))
                .setOngoing(true)

            CriticalAlertPresentation.SILENT_COMPANION -> builder
                .setSilent(true)
                .setTimeoutAfter(CRITICAL_ALERT_COMPANION_TIMEOUT_MS)
        }
        return builder.build()
    }

    



    private fun createActivityIntent(payload: CriticalAlertPayload, action: String?): PendingIntent {
        val intent = Intent(context, activityProvider.getActivityClass(ActivityType.CRITICAL_ALERT)).apply {
            if (action != null) this.action = action
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION, payload.conversationId)
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE, payload.title)
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE, payload.content)
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, payload.notificationId)
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, true)
            putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID, payload.roomId)
        }
        return PendingIntent.getActivity(
            context,
            payload.notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createStopSoundIntent(notificationId: Int): PendingIntent {
        val intent = Intent(context, StopCriticalAlertSoundReceiver::class.java).apply {
            action = MessageNotificationUtil.STOP_CRITICAL_ALERT_SOUND
            `package` = context.packageName
            putExtra("notification_id", notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        



        const val CRITICAL_ALERT_COMPANION_TIMEOUT_MS = 60_000L
    }
}
