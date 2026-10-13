package com.difft.android.call.manager

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RawRes
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Framework seam for [CallRingtoneManager]: builds a fresh [Ringtone] for a raw resource and
 * reports whether the ringer is audible. Kept separate so the manager's state machine can be
 * unit-tested without Android.
 */
@Singleton
class CallRingtoneSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Returns a new [Ringtone] instance on every call; never reuse one that has been stopped. */
    fun create(@RawRes resId: Int): Ringtone? =
        RingtoneManager.getRingtone(context, "android.resource://${context.packageName}/$resId".toUri())

    fun isRingerAudible(): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /**
     * What the user configured as the sound of the incoming-call notification channel. The system
     * plays that sound itself when the call notification is posted, so a user-picked sound replaces
     * the app ringtone. A channel created without `setSound` reports
     * [Settings.System.DEFAULT_NOTIFICATION_URI]; null means the user chose "None", which only
     * silences the system's own notification sound.
     */
    fun channelSoundPolicy(channelId: String): ChannelSoundPolicy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ChannelSoundPolicy.DEFAULT
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.getNotificationChannel(channelId) ?: return ChannelSoundPolicy.DEFAULT
        val sound = channel.sound
        return when {
            sound == null -> ChannelSoundPolicy.SILENT
            sound == Settings.System.DEFAULT_NOTIFICATION_URI -> ChannelSoundPolicy.DEFAULT
            else -> ChannelSoundPolicy.CUSTOM
        }
    }
}

/** Channel sound state: stock default, a user-picked sound (replaces the app ringtone), or "None". */
enum class ChannelSoundPolicy { DEFAULT, CUSTOM, SILENT }
