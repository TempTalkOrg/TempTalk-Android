package com.difft.android.setting.notificationtest

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationTestKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR

/**
 * Posts the test notifications through the app's real MESSAGE and CALL channels (#1350). Each test
 * has a fixed id, so a repeated test replaces the previous notification, and a timeout, so a
 * forgotten one disappears on its own.
 */
class NotificationTestPoster @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** True only when the notification was handed to the system; a missing channel or failure is false. */
    fun post(kind: NotificationTestKind): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channelId = channelOf(kind)
        if (nm.getNotificationChannel(channelId) == null) {
            L.w { "[NotificationTest] post skipped kind=${kind.logName} reason=no_channel" }
            return false
        }
        return try {
            nm.notify(idOf(kind), build(kind, channelId))
            true
        } catch (e: RuntimeException) {
            L.w { "[NotificationTest] post failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
            false
        }
    }

    fun cancel(kind: NotificationTestKind) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(idOf(kind))
        } catch (e: RuntimeException) {
            L.w { "[NotificationTest] cancel failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
        }
    }

    private fun build(kind: NotificationTestKind, channelId: String): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(BaseR.drawable.base_ic_notification_small)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(testPageIntent(kind))
        return if (kind == NotificationTestKind.CALL) {
            builder
                .setContentTitle(context.getString(ChatR.string.notification_test_call_notification_title))
                .setContentText(context.getString(ChatR.string.notification_test_call_notification_text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(callScreenIntent(), true)
                .setTimeoutAfter(CALL_TIMEOUT_MS)
                .build()
        } else {
            builder
                .setContentTitle(context.getString(ChatR.string.notification_test_notification_title))
                .setContentText(context.getString(ChatR.string.notification_test_notification_text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(MESSAGE_TIMEOUT_MS)
                .build()
        }
    }

    /** Tapping any test notification returns to the existing test page. */
    private fun testPageIntent(kind: NotificationTestKind): PendingIntent = PendingIntent.getActivity(
        context,
        idOf(kind),
        Intent(context, NotificationTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** CLEAR_TASK: a test call screen left open earlier must not absorb the launch, or onCreate would not run. */
    private fun callScreenIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        CALL_SCREEN_REQUEST_CODE,
        Intent(context, NotificationTestCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val MESSAGE_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 20_000L
        private const val CALL_SCREEN_REQUEST_CODE = 1_350_100

        fun channelOf(kind: NotificationTestKind): String =
            if (kind == NotificationTestKind.CALL) NotificationChannelIds.CALL else NotificationChannelIds.MESSAGE

        fun idOf(kind: NotificationTestKind): Int = when (kind) {
            NotificationTestKind.BANNER -> 1_350_001
            NotificationTestKind.LOCKSCREEN -> 1_350_002
            NotificationTestKind.CALL -> 1_350_003
        }
    }
}
