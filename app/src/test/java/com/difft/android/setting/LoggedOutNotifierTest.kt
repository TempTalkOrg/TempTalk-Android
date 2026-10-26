package com.difft.android.setting

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** #1354: the logged-out notice moves to OTHER_ALERTS and is posted only on a verified channel. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LoggedOutNotifierTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `a missing OTHER_ALERTS channel is re-created before the notice is posted`() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        LoggedOutNotifier(app).post()

        val channel = nm.getNotificationChannel(NotificationChannelIds.OTHER_ALERTS)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(NotificationChannelIds.OTHER_ALERTS, shadowOf(nm).allNotifications.single().channelId)
    }

    @Test
    fun `the notice is dropped without a crash when the channel cannot be created`() {
        val capped = mockk<NotificationManager>(relaxed = true) {
            every { getNotificationChannel(any()) } returns null
            every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
            every { areNotificationsEnabled() } returns true
        }
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, capped)

        LoggedOutNotifier(app).post()

        verify(exactly = 0) { capped.notify(any(), any(), any()) }
        verify(exactly = 0) { capped.notify(any<Int>(), any()) }
    }
}
