package com.difft.android.setting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification state machine for the in-app APK upgrade flow: progress → verifying →
 * complete/failed. Two ids back a single-VISIBLE-notification invariant:
 * - [NOTIFICATION_ID]: progress/verifying — the FGS notification bound via startForeground.
 * - [NOTIFICATION_ID_TERMINAL]: complete/failed.
 *
 * Terminal states use their own id because stopForeground(DETACH) triggers an async AMS re-post
 * of the FGS notification (to strip the foreground flag) that would overwrite a same-id terminal
 * update with stale "verifying" content. The invariant is held by explicit cross-id cancels:
 * a terminal post cancels [NOTIFICATION_ID]; a new download cancels [NOTIFICATION_ID_TERMINAL].
 *
 * Strip constraint: this whole file is deleted on google/f-droid channels (no in-app download),
 * so all upgrade-notification symbols must stay here.
 */
@Singleton
class AppUpgradeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "app_upgrade"
        const val NOTIFICATION_ID = 0x5057

        // Terminal (complete/failed) notification id, kept distinct from NOTIFICATION_ID and from
        // REQUEST_RETRY (NOTIFICATION_ID + 1) so ids and request codes never collide.
        const val NOTIFICATION_ID_TERMINAL = NOTIFICATION_ID + 2

        // Distinct request codes so the install PendingIntent and the retry PendingIntent
        // never collide (PendingIntent equality ignores extras).
        private const val REQUEST_INSTALL = NOTIFICATION_ID
        private const val REQUEST_RETRY = NOTIFICATION_ID + 1
        private const val MIME_APK = "application/vnd.android.package-archive"
    }

    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    @Volatile
    private var channelReady = false

    /**
     * Lazily creates the upgrade channel. IMPORTANCE_DEFAULT + no badge; the channel default
     * sound is intentionally kept (do NOT setSound(null)) so complete/failed alert once.
     */
    private fun ensureChannel() {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.upgrade_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
        channelReady = true
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)

    /** Ongoing, silent progress notification. Reused by startForeground; null percent → indeterminate. */
    fun buildProgress(percent: Int?): Notification {
        ensureChannel()
        val builder = baseBuilder()
            .setContentTitle(context.getString(R.string.upgrade_notification_downloading))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (percent == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    fun showProgress(percent: Int?) {
        notifyGuarded(NOTIFICATION_ID, buildProgress(percent))
    }

    /** Indeterminate "verifying" state shown while SHA256 + signature check runs. */
    fun showVerifying() {
        ensureChannel()
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.upgrade_notification_verifying))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        notifyGuarded(NOTIFICATION_ID, notification)
    }

    /**
     * Terminal success: tapping launches the system installer directly (notification trampoline
     * limits allow launching an Activity, not a Service, from a notification tap).
     * FileProvider duplication with UpdateManager is intentional (avoids touching a partially-stripped
     * file). On getUriForFile failure: log and do NOT post — the foreground dialog path still installs.
     */
    fun showComplete(apkFile: File) {
        ensureChannel()
        val uri = try {
            val authority = context.applicationContext.packageName + ".provider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (e: Exception) {
            L.e { "[AppUpgradeNotifier] getUriForFile failed: ${e.stackTraceToString()}" }
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, MIME_APK)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_INSTALL,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.upgrade_notification_complete))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        cancel()
        notifyGuarded(NOTIFICATION_ID_TERMINAL, notification)
    }

    /**
     * Terminal failure: tapping restarts AppUpgradeService (as a foreground service) with the same
     * params to retry. Not auto-cancelled — the service clears it via cancelTerminal() when a new
     * download starts.
     */
    fun showFailed(url: String, apkHash: String, filePath: String, isForce: Boolean) {
        ensureChannel()
        val retryIntent = Intent(context, AppUpgradeService::class.java).apply {
            putExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_URL, url)
            putExtra(UpdateManager.INTENT_PARAM_APK_VERIFY_HASH, apkHash)
            putExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH, filePath)
            putExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, isForce)
        }
        val contentIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_RETRY,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.upgrade_notification_failed))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        cancel()
        notifyGuarded(NOTIFICATION_ID_TERMINAL, notification)
    }

    /** Cancels the progress/verifying (FGS) notification. */
    fun cancel() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            L.w { "[AppUpgradeNotifier] cancel failed: ${e.message}" }
        }
    }

    /** Cancels a lingering terminal (complete/failed) notification, e.g. when retry starts. */
    fun cancelTerminal() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_TERMINAL)
        } catch (e: Exception) {
            L.w { "[AppUpgradeNotifier] cancelTerminal failed: ${e.message}" }
        }
    }

    /**
     * Posts to [id], guarded by the notification-enabled check + broad catch
     * (matches MessageNotificationUtil). When notifications are disabled the flow degrades
     * silently — the existing Toast/dialog path still covers install.
     */
    private fun notifyGuarded(id: Int, notification: Notification) {
        if (!notificationManager.areNotificationsEnabled()) {
            L.i { "[AppUpgradeNotifier] notifications disabled, skip id=$id" }
            return
        }
        try {
            notificationManager.notify(id, notification)
        } catch (e: Exception) {
            L.w { "[AppUpgradeNotifier] notify failed id=$id: ${e.stackTraceToString()}" }
        }
    }
}

/**
 * Stateful throttle for progress notification updates. Pure inputs (percent + nowMs) with no clock
 * or IO dependency, so it is unit-testable. Emits when the percent step or the time interval
 * threshold is crossed since the last EMITTED update; the first call and 100% always emit
 * (final progress must never be dropped). Thresholds accumulate from the last emit, not last call.
 */
class ProgressThrottle(
    private val minStepPercent: Int = 5,
    private val minIntervalMs: Long = 500L
) {
    private var lastPercent = -1
    private var lastEmitMs = 0L

    fun shouldEmit(percent: Int, nowMs: Long): Boolean {
        if (lastPercent < 0 || percent >= 100 ||
            percent - lastPercent >= minStepPercent ||
            nowMs - lastEmitMs >= minIntervalMs
        ) {
            lastPercent = percent
            lastEmitMs = nowMs
            return true
        }
        return false
    }
}
