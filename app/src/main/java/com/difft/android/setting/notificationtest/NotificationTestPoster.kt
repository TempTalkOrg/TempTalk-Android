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
import com.difft.android.base.notification.NotificationTestRun
import com.difft.android.base.notification.NotificationTestRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR





class NotificationTestPoster @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runs: NotificationTestRuns,
) {

    
    suspend fun post(run: NotificationTestRun): Boolean = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (!runs.owns(run)) return@withContext false
        val kind = run.kind
        val nm = context.getSystemService(NotificationManager::class.java) ?: return@withContext false
        val channelId = channelOf(kind)
        if (nm.getNotificationChannel(channelId) == null) {
            L.w { "[NotificationTest] post skipped kind=${kind.logName} reason=no_channel" }
            return@withContext false
        }
        val posted = try {
            nm.notify(tagOf(run.runId), idOf(kind), build(run, channelId))
            true
        } catch (e: RuntimeException) {
            L.w { "[NotificationTest] post failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
            false
        }
        currentCoroutineContext().ensureActive()
        posted
    }

    suspend fun cancel(runId: String, kind: NotificationTestKind) = withContext(Dispatchers.Main.immediate) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(tagOf(runId), idOf(kind))
        } catch (e: RuntimeException) {
            L.w { "[NotificationTest] cancel failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
        }
    }

    private fun build(run: NotificationTestRun, channelId: String): Notification {
        val kind = run.kind
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(BaseR.drawable.base_ic_notification_small)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(testPageIntent(run))
        return if (kind == NotificationTestKind.CALL) {
            builder
                .setContentTitle(context.getString(ChatR.string.notification_test_call_notification_title))
                .setContentText(context.getString(ChatR.string.notification_test_call_notification_text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(callScreenIntent(run), true)
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

    private fun testPageIntent(run: NotificationTestRun): PendingIntent = PendingIntent.getActivity(
        context,
        idOf(run.kind),
        NotificationTestReturnRoute.returnIntent(context, run),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    
    private fun callScreenIntent(run: NotificationTestRun): PendingIntent = PendingIntent.getActivity(
        context,
        CALL_SCREEN_REQUEST_CODE,
        Intent(context, NotificationTestCallActivity::class.java)
            .setData(android.net.Uri.parse("temptalk-internal://notification-test/call/${run.runId}"))
            .putExtra(NotificationTestCallActivity.RUN_ID, run.runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        fun tagOf(runId: String): String = "notification-test:$runId"

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
