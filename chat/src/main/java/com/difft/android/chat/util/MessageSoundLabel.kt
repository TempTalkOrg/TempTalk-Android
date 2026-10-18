package com.difft.android.chat.util

import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.chat.R

/** The sound a chat or the MESSAGE category plays, as the trailing value of a sound row. */
object MessageSoundLabel {

    /**
     * No sound is "None". Otherwise the system's own title for the sound; a blank title, a title
     * that is just the URI's last segment or a number, or any failure (for example a file on
     * external storage without media permission) is "Custom sound".
     * No permission is ever requested.
     */
    fun resolve(sound: Uri?, titleOf: (Uri) -> String?, none: String, custom: String): String {
        if (sound == null) return none
        val title = runCatching { titleOf(sound) }.getOrNull()?.trim()
        return title?.takeIf { isRealTitle(it, sound) } ?: custom
    }

    /**
     * An unreadable custom file often "resolves" to its URI's last segment or a numeric media id
     * rather than failing, so those count as unknown, like a blank title.
     */
    private fun isRealTitle(title: String, sound: Uri): Boolean =
        title.isNotEmpty() && title != sound.lastPathSegment && !title.all { it.isDigit() }

    /**
     * The category a conversation actually plays through: its own channel once one exists, otherwise
     * the shared MESSAGE channel (what the chat uses until the per-chat channel is created).
     */
    fun effectiveChannelId(conversationId: String, perChatChannelExists: Boolean): String =
        if (perChatChannelExists) NotificationChannelIds.MESSAGE + conversationId else NotificationChannelIds.MESSAGE

    /** The MESSAGE category's sound label; null below API 26. Call on IO. */
    fun read(context: Context): String? = readChannel(context) { NotificationChannelIds.MESSAGE }

    /** The sound label for a conversation; null below API 26. Call on IO. */
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
