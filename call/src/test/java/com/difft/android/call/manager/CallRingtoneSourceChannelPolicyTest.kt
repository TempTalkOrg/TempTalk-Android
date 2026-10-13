package com.difft.android.call.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CallRingtoneSource.channelSoundPolicy] decides whether the app plays its own ringtone for an
 * incoming call or defers to the sound the user assigned to the call notification channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRingtoneSourceChannelPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val source = CallRingtoneSource(context)

    private fun createChannel(configure: NotificationChannel.() -> Unit = {}) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH).apply(configure)
        )
    }

    @Test
    fun `missing channel falls back to the app ringtone`() {
        assertEquals(ChannelSoundPolicy.DEFAULT, source.channelSoundPolicy(CHANNEL_ID))
    }

    @Test
    fun `channel created without a sound keeps the app ringtone`() {
        createChannel()

        assertEquals(ChannelSoundPolicy.DEFAULT, source.channelSoundPolicy(CHANNEL_ID))
    }

    @Test
    fun `channel explicitly set to the stock default keeps the app ringtone`() {
        createChannel { setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null) }

        assertEquals(ChannelSoundPolicy.DEFAULT, source.channelSoundPolicy(CHANNEL_ID))
    }

    @Test
    fun `user-picked channel sound defers to the system`() {
        createChannel { setSound("content://media/external/audio/media/42".toUri(), null) }

        assertEquals(ChannelSoundPolicy.CUSTOM, source.channelSoundPolicy(CHANNEL_ID))
    }

    @Test
    fun `channel sound set to none is reported as silent`() {
        createChannel { setSound(null, null) }

        assertEquals(ChannelSoundPolicy.SILENT, source.channelSoundPolicy(CHANNEL_ID))
    }

    private companion object {
        const val CHANNEL_ID = "CALL"
    }
}
