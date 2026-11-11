package com.difft.android.setting

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.PackageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class LoggedOutNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    




    fun post() {
        if (!NotificationChannelSpecs.ensure(context, CHANNEL_ID)) {
            L.w { "[Logout] logged-out notice skipped: channel missing id=$CHANNEL_ID" }
            return
        }
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
            
            L.w { "[Logout] posting logged-out notification failed: ${e.message}" }
        }
    }

    private companion object {
        const val CHANNEL_ID = NotificationChannelIds.OTHER_ALERTS

        
        
        const val NOTIFICATION_ID = 10001
    }
}
