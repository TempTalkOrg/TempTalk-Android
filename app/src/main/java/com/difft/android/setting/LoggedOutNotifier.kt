package com.difft.android.setting

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "This device has been logged out" notification, posted only when a passive logout fires with no
 * resumed Activity — the user would otherwise get an unexplained process death and no explanation
 * until the next launch.
 *
 * Tapping it opens the launcher intent, which routes to the login screen because the credential is
 * already cleared by then.
 */
@Singleton
class LoggedOutNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    /** Called at startup alongside the other notification channels. Idempotent. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_ID,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            notificationManager.createNotificationChannel(channel)
        }.onFailure { L.w { "[Logout] ensureChannel failed: ${it.message}" } }
    }

    /**
     * Posts the notice. Re-creates the channel first: the logout can fire on a sticky-service
     * revival path where the startup channel step is not guaranteed to have run.
     */
    fun post() {
        ensureChannel()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK) }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(context.getString(com.difft.android.chat.R.string.chat_state_auth_failed_tips))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(com.difft.android.chat.R.string.chat_state_auth_failed_tips))
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // The permission check must sit in this method: lint's MissingPermission does not follow
        // the guard into a helper, and areNotificationsEnabled() alone does not satisfy it.
        // Below API 33 the permission does not exist and would read as denied, hence the SDK gate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            L.i { "[Logout] POST_NOTIFICATIONS not granted, skipping logged-out notice" }
            return
        }
        if (!notificationManager.areNotificationsEnabled()) {
            L.i { "[Logout] notifications disabled, skipping logged-out notice" }
            return
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Never let a notification failure block the exit that follows it.
            L.w { "[Logout] posting logged-out notification failed: ${e.message}" }
        }
    }

    private companion object {
        // Id doubles as the display name, matching the existing channel convention in this app.
        const val CHANNEL_ID = "ACCOUNT"

        // Distinct from every other notification id in the repo (10000 message summary,
        // 0x5057 upgrade, 313399 message FGS, 3456 call FGS).
        const val NOTIFICATION_ID = 10001
    }
}
