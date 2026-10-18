package com.difft.android.chat.util

import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The sound row's value: a title when the system can name the sound, otherwise None or Custom sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageSoundLabelTest {

    private fun resolve(sound: Uri?, titleOf: (Uri) -> String?) =
        MessageSoundLabel.resolve(sound, titleOf, none = "None", custom = "Custom sound")

    @Test
    fun `no sound is None without asking for a title`() {
        assertEquals("None", resolve(null) { error("must not resolve") })
    }

    @Test
    fun `the default sound shows the title the system gives it`() {
        assertEquals(
            "Default (Spaceline)",
            resolve(Settings.System.DEFAULT_NOTIFICATION_URI) { "Default (Spaceline)" },
        )
    }

    @Test
    fun `another sound shows its own title`() {
        val uri = "content://media/internal/audio/media/42".toUri()
        assertEquals("Chime", resolve(uri) { if (it == uri) "Chime" else null })
    }

    @Test
    fun `a blank or missing title falls back to Custom sound`() {
        val uri = "content://media/external/audio/media/7".toUri()
        assertEquals("Custom sound", resolve(uri) { "  " })
        assertEquals("Custom sound", resolve(uri) { "" })
        assertEquals("Custom sound", resolve(uri) { null })
    }

    @Test
    fun `a title that is the uri segment or a bare number counts as unknown`() {
        val uri = "content://media/external/audio/media/7".toUri()
        assertEquals("Custom sound", resolve(uri) { "7" })
        assertEquals("Custom sound", resolve(uri) { "12345" })
        val file = "file:///sdcard/Notifications/ding_custom.ogg".toUri()
        assertEquals("Custom sound", resolve(file) { "ding_custom.ogg" })
        assertEquals("ding", resolve(file) { " ding " })
        assertEquals("Chime 2", resolve(uri) { "Chime 2" })
    }

    @Test
    fun `any failure while naming the sound falls back to Custom sound`() {
        val uri = "content://media/external/audio/media/7".toUri()
        assertEquals("Custom sound", resolve(uri) { throw SecurityException("no media permission") })
        assertEquals("Custom sound", resolve(uri) { throw IllegalStateException() })
    }

    @Test
    fun `a chat plays through its own channel once it exists and through MESSAGE before that`() {
        assertEquals("MESSAGE+8613800000000", MessageSoundLabel.effectiveChannelId("+8613800000000", perChatChannelExists = true))
        assertEquals("MESSAGE", MessageSoundLabel.effectiveChannelId("+8613800000000", perChatChannelExists = false))
    }

    @Test
    fun `the conversation label reads the per-chat channel when present and MESSAGE otherwise`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // MESSAGE plays no sound; the per-chat channel keeps the default sound (blank title -> Custom sound).
        nm.createNotificationChannel(
            android.app.NotificationChannel("MESSAGE", "Messages", android.app.NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
        assertEquals(context.getString(com.difft.android.chat.R.string.notification_sound_none), MessageSoundLabel.readForConversation(context, "chat1"))

        nm.createNotificationChannel(
            android.app.NotificationChannel("MESSAGEchat1", "Chat", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                setSound("content://media/external/audio/media/9".toUri(), null)
            }
        )
        assertEquals(
            context.getString(com.difft.android.chat.R.string.notification_sound_custom),
            MessageSoundLabel.readForConversation(context, "chat1"),
        )
    }
}
