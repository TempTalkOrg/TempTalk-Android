package com.difft.android.base.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.difft.android.base.log.lumberjack.L


















object NotificationChannelSpecs {

    
    @Volatile
    var verifiedForegroundChannel: String? = null
        private set

    
    fun ensure(context: Context, id: String): Boolean {
        val nm = notificationManager(context) ?: return false
        return try {
            if (nm.getNotificationChannel(id) == null) create(context, nm, id)
            nm.getNotificationChannel(id) != null
        } catch (e: Exception) {
            L.w { "[NotificationChannel] ensure failed id=$id error=${e.javaClass.simpleName}" }
            false
        }
    }

    





    fun foregroundChannel(context: Context): String? = (lookupForeground(context) as? Lookup.Found)?.id

    
    fun preflightForegroundChannel(context: Context, service: String): String? {
        val id = foregroundChannel(context)
        if (id == null) {
            L.e { "[NotificationChannel] no foreground channel, start skipped service=$service" }
        } else {
            verifiedForegroundChannel = id
        }
        return id
    }

    



    fun serviceForegroundChannel(context: Context, service: String): String? {
        val id = when (val lookup = lookupForeground(context)) {
            is Lookup.Found -> lookup.id
            Lookup.Missing -> null
            Lookup.Failed -> verifiedForegroundChannel.also {
                L.w { "[NotificationChannel] lookup failed, preflight id used service=$service id=$it" }
            }
        }
        if (id == null) L.e { "[NotificationChannel] no foreground channel, promotion skipped service=$service" }
        return id
    }

    
    fun applyForegroundFallback(builder: NotificationCompat.Builder, channelId: String): NotificationCompat.Builder =
        if (channelId == NotificationChannelIds.RUNNING_IN_BACKGROUND) {
            builder
        } else {
            builder.setSilent(true).setOnlyAlertOnce(true)
        }

    
    fun build(context: Context, id: String): NotificationChannel {
        val name = NotificationChannelIds.nameRes(id)?.let { context.getString(it) } ?: id
        return when (id) {
            NotificationChannelIds.OTHER_ALERTS ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                }
            NotificationChannelIds.RUNNING_IN_BACKGROUND ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            else -> error("No shared channel spec for id=$id")
        }
    }

    @VisibleForTesting
    fun resetVerifiedForegroundChannel() {
        verifiedForegroundChannel = null
    }

    private fun lookupForeground(context: Context): Lookup {
        val nm = notificationManager(context) ?: return Lookup.Failed
        return try {
            val background = NotificationChannelIds.RUNNING_IN_BACKGROUND
            if (nm.getNotificationChannel(background) == null) create(context, nm, background)
            when {
                nm.getNotificationChannel(background) != null -> Lookup.Found(background)
                nm.getNotificationChannel(NotificationChannelIds.MESSAGE) != null -> {
                    L.w { "[NotificationChannel] foreground fallback id=${NotificationChannelIds.MESSAGE}" }
                    Lookup.Found(NotificationChannelIds.MESSAGE)
                }
                else -> Lookup.Missing
            }
        } catch (e: Exception) {
            L.w { "[NotificationChannel] foreground lookup failed error=${e.javaClass.simpleName}" }
            Lookup.Failed
        }
    }

    
    private fun create(context: Context, nm: NotificationManager, id: String) {
        try {
            nm.createNotificationChannel(build(context, id))
        } catch (e: Exception) {
            L.w { "[NotificationChannel] create failed id=$id error=${e.javaClass.simpleName}" }
        }
    }

    private fun notificationManager(context: Context): NotificationManager? = try {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    } catch (e: Exception) {
        L.w { "[NotificationChannel] no notification manager error=${e.javaClass.simpleName}" }
        null
    }

    private sealed interface Lookup {
        data class Found(val id: String) : Lookup
        data object Missing : Lookup
        data object Failed : Lookup
    }
}
