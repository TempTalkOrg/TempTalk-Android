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







@Singleton
class MigrationTransferNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager? =
        context.getSystemService<NotificationManager>()

    




    @Volatile
    var returnToTransferIntent: PendingIntent? = null

    
    @Volatile
    private var runningChannelId: String? = null

    



    fun buildRunning(percent: Int, channelId: String): Notification {
        runningChannelId = channelId
        return buildRunningNotification(percent, channelId)
    }

    
    fun updateProgress(percent: Int) {
        val manager = notificationManager ?: return
        val channelId = runningChannelId ?: return
        runCatching { manager.notify(NOTIFICATION_ID_RUNNING, buildRunningNotification(percent, channelId)) }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] notification update failed: ${it.message}" } }
    }

    
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
        
        const val NOTIFICATION_ID_RUNNING = 0xCAFE0101.toInt()

        
        const val NOTIFICATION_ID_RESULT = 0xCAFE0102.toInt()

        private const val REQUEST_CODE_CANCEL = 0xCAFE0103.toInt()

        
        const val ACTION_CANCEL = "com.difft.migration.p2p.ACTION_CANCEL"
    }
}
