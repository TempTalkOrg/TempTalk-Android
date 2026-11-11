package com.difft.android.chat.util

import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.chat.R


object MessageSoundLabel {

    





    fun resolve(sound: Uri?, titleOf: (Uri) -> String?, none: String, custom: String): String {
        if (sound == null) return none
        val title = runCatching { titleOf(sound) }.getOrNull()?.trim()
        return title?.takeIf { isRealTitle(it, sound) } ?: custom
    }

    



    private fun isRealTitle(title: String, sound: Uri): Boolean =
        title.isNotEmpty() && title != sound.lastPathSegment && !title.all { it.isDigit() }

    



    fun effectiveChannelId(conversationId: String, perChatChannelExists: Boolean): String =
        if (perChatChannelExists) NotificationChannelIds.MESSAGE + conversationId else NotificationChannelIds.MESSAGE

    
    fun read(context: Context): String? = readChannel(context) { NotificationChannelIds.MESSAGE }

    
    fun readForConversation(context: Context, conversationId: String): String? = readChannel(context) { nm ->
        effectiveChannelId(
            conversationId,
            perChatChannelExists = nm.getNotificationChannel(NotificationChannelIds.MESSAGE + conversationId) != null,
        )
    }

    private fun readChannel(context: Context, channelId: (NotificationManager) -> String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(channelId(nm)) ?: return null
        return resolve(
            sound = channel.sound,
            titleOf = { uri ->
                RingtoneManager.getRingtone(context, uri)?.let { ringtone ->
                    try {
                        ringtone.getTitle(context)
                    } finally {
                        runCatching { ringtone.stop() }
                    }
                }
            },
            none = context.getString(R.string.notification_sound_none),
            custom = context.getString(R.string.notification_sound_custom),
        )
    }
}
