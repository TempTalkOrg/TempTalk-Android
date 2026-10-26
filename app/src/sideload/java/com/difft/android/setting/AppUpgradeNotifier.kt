package com.difft.android.setting

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
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
 * Progress/verifying sit on the foreground channel the service verified; complete/failed on
 * [NotificationChannelIds.OTHER_ALERTS] (#1354). A notification whose channel cannot be verified
 * is dropped.
 *
 * Compiled only for sideload channels (src/sideload).
 */
@Singleton
class AppUpgradeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val NOTIFICATION_ID = 0x5057

        // Terminal (complete/failed) notification id, kept distinct from NOTIFICATION_ID and from
        // REQUEST_RETRY (NOTIFICATION_ID + 1) so ids and request codes never collide.
        const val NOTIFICATION_ID_TERMINAL = NOTIFICATION_ID + 2

        // Distinct request codes so the install PendingIntent and the retry PendingIntent
        // never collide (PendingIntent equality ignores extras).
        private const val REQUEST_INSTALL = NOTIFICATION_ID
        private const val REQUEST_RETRY = NOTIFICATION_ID + 1
    }

    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    /** The channel the foreground notification was last built on; progress updates stay on it. */
    @Volatile
    private var progressChannelId: String? = null

    private fun baseBuilder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)

    /**
     * Ongoing, silent progress notification on [channelId], which the caller verified. Reused by
     * startForeground; null percent → indeterminate.
     */
    fun buildProgress(percent: Int?, channelId: String): Notification {
        progressChannelId = channelId
        val builder = baseBuilder(channelId)
            .setContentTitle(context.getString(R.string.upgrade_notification_downloading))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (percent == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    fun showProgress(percent: Int?) {
        val channelId = progressChannel() ?: return
        notifyGuarded(NOTIFICATION_ID, buildProgress(percent, channelId))
    }

    /** Indeterminate "verifying" state shown while SHA256 + signature check runs. */
    fun showVerifying() {
        val channelId = progressChannel() ?: return
        val builder = baseBuilder(channelId)
            .setContentTitle(context.getString(R.string.upgrade_notification_verifying))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
        notifyGuarded(NOTIFICATION_ID, NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build())
    }

    /**
     * Terminal success: tapping launches the system installer directly (notification trampoline
     * limits allow launching an Activity, not a Service, from a notification tap).
     * On getUriForFile failure: log and do NOT post — the foreground dialog path still installs.
     */
    fun showComplete(apkFile: File) {
        if (!ensureAlertChannel()) return
        val uri = try {
            ApkUpdateFiles.contentUri(context, apkFile)
        } catch (e: Exception) {
            L.e { "[AppUpgradeNotifier] getUriForFile failed: ${e.stackTraceToString()}" }
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, ApkUpdateFiles.MIME_APK)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_INSTALL,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder(NotificationChannelIds.OTHER_ALERTS)
            .setContentTitle(context.getString(R.string.upgrade_notification_complete))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        cancel()
        notifyGuarded(NOTIFICATION_ID_TERMINAL, notification)
    }

    /**
     * Terminal failure: tapping retries with the same params through [AppUpgradeRetryReceiver],
     * which runs the starter's channel preflight before starting AppUpgradeService; a direct
     * foreground-service PendingIntent would start it with no preflight. Not auto-cancelled — the
     * service clears it via cancelTerminal() when a new download starts.
     */
    fun showFailed(url: String, apkHash: String, filePath: String, isForce: Boolean) {
        if (!ensureAlertChannel()) return
        val retryIntent = AppUpgradeRetryReceiver.intent(context, url, apkHash, filePath, isForce)
        val contentIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_RETRY,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = baseBuilder(NotificationChannelIds.OTHER_ALERTS)
            .setContentTitle(context.getString(R.string.upgrade_notification_failed))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        cancel()
        notifyGuarded(NOTIFICATION_ID_TERMINAL, notification)
    }

    private fun progressChannel(): String? {
        val channelId = progressChannelId ?: NotificationChannelSpecs.foregroundChannel(context)
        if (channelId == null) L.w { "[AppUpgradeNotifier] progress skipped: channel missing" }
        return channelId
    }

    private fun ensureAlertChannel(): Boolean {
        val ready = NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS)
        if (!ready) L.w { "[AppUpgradeNotifier] terminal skipped: channel missing id=${NotificationChannelIds.OTHER_ALERTS}" }
        return ready
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
