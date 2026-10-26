package com.difft.android.base.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.difft.android.base.log.lumberjack.L

/**
 * Specs of the shared [NotificationChannelIds.OTHER_ALERTS] and
 * [NotificationChannelIds.RUNNING_IN_BACKGROUND] channels, and the verify-before-use rules that
 * keep every post and every `startForeground` off a missing channel (#1354).
 *
 * A `startForeground` on a missing channel makes the system kill the app asynchronously, beyond
 * any catch, so a foreground service only ever promotes on an id verified to exist:
 * 1. A starter calls [preflightForegroundChannel] before `startForegroundService`; a null result
 *    means the service is not started. A verified id is kept in [verifiedForegroundChannel].
 * 2. The service calls [serviceForegroundChannel] at every promotion: the live lookup's id, or the
 *    preflight id when the lookup itself throws, or null, on which it stops without promoting.
 *
 * App-owned [NotificationChannelIds.KEEP] channels never disappear once verified: only
 * [NotificationChannelIds.OBSOLETE] ids are deleted, and only the system or the app can delete.
 * Starters and services share one process (no service declares `android:process`). Nothing here
 * touches DataStore, so every call is safe on the main thread.
 */
object NotificationChannelSpecs {

    /** The foreground channel the latest preflight in this process verified; null in a fresh process. */
    @Volatile
    var verifiedForegroundChannel: String? = null
        private set

    /** Creates [id] when missing; returns whether it exists afterwards. Never throws. */
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

    /**
     * The first foreground channel that exists: [NotificationChannelIds.RUNNING_IN_BACKGROUND]
     * (created when missing), else [NotificationChannelIds.MESSAGE], present since the first
     * release on any device old enough to hit the per-app channel cap. Null when neither exists
     * or the lookup throws.
     */
    fun foregroundChannel(context: Context): String? = (lookupForeground(context) as? Lookup.Found)?.id

    /** Starter-side check before `startForegroundService`; null means do not start [service]. */
    fun preflightForegroundChannel(context: Context, service: String): String? {
        val id = foregroundChannel(context)
        if (id == null) {
            L.e { "[NotificationChannel] no foreground channel, start skipped service=$service" }
        } else {
            verifiedForegroundChannel = id
        }
        return id
    }

    /**
     * Service-side check at every promotion: the live result, or the preflight id when the lookup
     * throws. Null means stop without calling `startForeground`.
     */
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

    /** Keeps a foreground notification quiet when it fell back to the alerting MESSAGE channel. */
    fun applyForegroundFallback(builder: NotificationCompat.Builder, channelId: String): NotificationCompat.Builder =
        if (channelId == NotificationChannelIds.RUNNING_IN_BACKGROUND) {
            builder
        } else {
            builder.setSilent(true).setOnlyAlertOnce(true)
        }

    /** Spec of an app-wide channel owned here; the message, call and critical-alert specs live in `:chat`. */
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

    /** A failed create (the per-app channel cap, a dead system server) is logged; callers re-read. */
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
