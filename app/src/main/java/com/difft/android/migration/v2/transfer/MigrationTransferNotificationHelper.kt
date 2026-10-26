package com.difft.android.migration.v2.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification surface for the transfer foreground service: progress, terminal result and a Cancel
 * action. Progress sits on the channel the service verified for its foreground notification, the
 * result on [NotificationChannelIds.OTHER_ALERTS] (#1354). All user-visible copy comes from
 * `migration_p2p_notification_*` string resources.
 */
@Singleton
class MigrationTransferNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager? =
        context.getSystemService<NotificationManager>()

    /**
     * Tap target of the ongoing notification: brings the transfer screen back to the front. Without
     * it, anything that buries the transfer UI leaves the user no way back to the running transfer.
     * The service is the single writer and sets it per run; null simply means no tap target.
     */
    @Volatile
    var returnToTransferIntent: PendingIntent? = null

    /** The channel the foreground notification was last built on; progress updates stay on it. */
    @Volatile
    private var runningChannelId: String? = null

    /**
     * `startForeground(...)` notification — ongoing, with progress bar + Cancel action — on
     * [channelId], which the service verified to exist.
     */
    fun buildRunning(percent: Int, channelId: String): Notification {
        runningChannelId = channelId
        return buildRunningNotification(percent, channelId)
    }

    /** Re-posts the ongoing notification at a refreshed [percent]; indeterminate when negative. */
    fun updateProgress(percent: Int) {
        val manager = notificationManager ?: return
        val channelId = runningChannelId ?: return
        runCatching { manager.notify(NOTIFICATION_ID_RUNNING, buildRunningNotification(percent, channelId)) }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] notification update failed: ${it.message}" } }
    }

    /** Terminal (success / failure) — dismissible; dropped when its channel cannot be verified. */
    fun showTerminal(title: String, body: String) {
        val manager = notificationManager ?: return
        if (!NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS)) {
            L.w { "[MigrationV2][P2P][FGS] terminal notification skipped: channel missing" }
            return
        }
        val notification = NotificationCompat.Builder(context, NotificationChannelIds.OTHER_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID_RESULT, notification) }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] terminal notification post failed: ${it.message}" } }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun buildRunningNotification(percent: Int, channelId: String): Notification {
        val indeterminate = percent < 0
        val clamped = percent.coerceIn(0, 100)
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.migration_p2p_notification_title_running))
            .setContentText(
                if (indeterminate) context.getString(R.string.migration_p2p_notification_text_running) else "$clamped%",
            )
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(100, clamped, indeterminate)
            .apply { returnToTransferIntent?.let { setContentIntent(it) } }
            .addAction(buildCancelAction())
        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    private fun buildCancelAction(): NotificationCompat.Action {
        val cancelIntent = Intent(ACTION_CANCEL).apply { setPackage(context.packageName) }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = context.getString(R.string.migration_p2p_notification_action_cancel)
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }

    companion object {
        /** Ongoing-notification id for `startForeground(...)`. Distinct from the backup ids. */
        const val NOTIFICATION_ID_RUNNING = 0xCAFE0101.toInt()

        /** Result-channel notification id (success + failure share the slot). */
        const val NOTIFICATION_ID_RESULT = 0xCAFE0102.toInt()

        private const val REQUEST_CODE_CANCEL = 0xCAFE0103.toInt()

        /** Cancel intent action; must match [MigrationTransferService.ACTION_CANCEL]. */
        const val ACTION_CANCEL = "com.difft.migration.p2p.ACTION_CANCEL"
    }
}
