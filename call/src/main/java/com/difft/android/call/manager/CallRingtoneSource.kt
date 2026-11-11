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






@Singleton
class CallRingtoneSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    
    fun create(@RawRes resId: Int): Ringtone? =
        RingtoneManager.getRingtone(context, "android.resource://${context.packageName}/$resId".toUri())

    fun isRingerAudible(): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    






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


enum class ChannelSoundPolicy { DEFAULT, CUSTOM, SILENT }
