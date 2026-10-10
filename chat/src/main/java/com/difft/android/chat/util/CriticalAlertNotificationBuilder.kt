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

/** Content of one critical-alert notification. [title] is the raw alert title, without the 🚨 prefix. */
data class CriticalAlertPayload(
    val conversationId: String,
    val title: String,
    val content: String,
    val notificationId: Int,
    val timestamp: Long,
    val roomId: String?,
)

enum class CriticalAlertPresentation {
    /** Background / voice-recording path: full-screen intent, ongoing, delete intent stops the alert sound. */
    FULL_SCREEN,

    /**
     * Foreground path: CriticalAlertActivity is the visible surface and owns the sound, so this
     * copy is silent, has no full-screen or delete intent, is swipeable, and times out.
     */
    SILENT_COMPANION,
}

/** Single owner of the critical-alert [Notification] construction. Stateless. */
class CriticalAlertNotificationBuilder @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val activityProvider: ActivityProvider,
) {

    fun build(payload: CriticalAlertPayload, channelId: String, presentation: CriticalAlertPresentation): Notification {
        val title = "🚨${payload.title}"

        // BigTextStyle pins the layout so it does not jump on the lock screen
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(payload.content)
            .setBigContentTitle(title)

        // The companion only re-surfaces the alert page on tap; CLICKED would auto-join the call
        // and, with the page already on top, would restart the alert sound.
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
            .setWhen(payload.timestamp) // fixed timestamp, so the notification is not reordered
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

    /**
     * Content (CLICKED for FULL_SCREEN, null for SILENT_COMPANION) and full-screen (null) intents
     * share requestCode = notificationId; equal intents carry identical extras, so an overwrite is harmless.
     */
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
        /**
         * Covers the CriticalAlertActivity exits that finish without cancelling the notification
         * (60 s auto-finish, DISMISS_BY_CONID, recents swipe, process death).
         */
        const val CRITICAL_ALERT_COMPANION_TIMEOUT_MS = 60_000L
    }
}
