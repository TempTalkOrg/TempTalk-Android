package com.difft.android.base.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1354: shared channel specs and the verify-before-use foreground channel rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelSpecsTest {

    private val channels = mutableMapOf<String, NotificationChannel>()
    private lateinit var nm: NotificationManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        nm = mockk(relaxed = true)
        every { nm.getNotificationChannel(any()) } answers { channels[firstArg()] }
        every { nm.createNotificationChannel(any<NotificationChannel>()) } answers {
            val channel = firstArg<NotificationChannel>()
            channels.putIfAbsent(channel.id, channel)
        }
        context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
    }

    @After
    fun tearDown() {
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
    }

    private fun capReached() {
        every { nm.createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
    }

    @Test
    fun `the shared specs match the design`() {
        val other = NotificationChannelSpecs.build(context, NotificationChannelIds.OTHER_ALERTS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, other.importance)
        assertFalse(other.canShowBadge())
        assertEquals("Other alerts", other.name.toString())
        val background = NotificationChannelSpecs.build(context, NotificationChannelIds.RUNNING_IN_BACKGROUND)
        assertEquals(NotificationManager.IMPORTANCE_LOW, background.importance)
        assertNull(background.sound)
        assertFalse(background.shouldVibrate())
        assertFalse(background.canShowBadge())
        assertEquals("Running in background", background.name.toString())
    }

    @Test
    @Config(qualifiers = "zh")
    fun `the shared channel names are localized`() {
        assertEquals("其他提醒", NotificationChannelSpecs.build(context, NotificationChannelIds.OTHER_ALERTS).name.toString())
        assertEquals("后台运行", NotificationChannelSpecs.build(context, NotificationChannelIds.RUNNING_IN_BACKGROUND).name.toString())
    }

    @Test
    fun `ensuring every shared channel creates no obsolete id`() {
        assertTrue(NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS))
        assertTrue(NotificationChannelSpecs.ensure(context, NotificationChannelIds.RUNNING_IN_BACKGROUND))
        NotificationChannelSpecs.foregroundChannel(context)

        assertEquals(setOf(NotificationChannelIds.OTHER_ALERTS, NotificationChannelIds.RUNNING_IN_BACKGROUND), channels.keys)
    }

    @Test
    fun `a throwing notification manager is reported as missing, never thrown`() {
        every { nm.getNotificationChannel(any()) } throws RuntimeException("dead system")

        assertFalse(NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS))
        assertNull(NotificationChannelSpecs.foregroundChannel(context))
        assertNull(NotificationChannelSpecs.preflightForegroundChannel(context, "Test"))
        assertNull(NotificationChannelSpecs.verifiedForegroundChannel)
    }

    @Test
    fun `a failed create is verified as missing`() {
        capReached()

        assertFalse(NotificationChannelSpecs.ensure(context, NotificationChannelIds.OTHER_ALERTS))
    }

    @Test
    fun `at the channel cap the foreground channel falls back to an existing MESSAGE`() {
        capReached()
        channels[NotificationChannelIds.MESSAGE] = NotificationChannel(NotificationChannelIds.MESSAGE, "Messages", NotificationManager.IMPORTANCE_HIGH)

        assertEquals(NotificationChannelIds.MESSAGE, NotificationChannelSpecs.preflightForegroundChannel(context, "Test"))
        assertEquals(NotificationChannelIds.MESSAGE, NotificationChannelSpecs.verifiedForegroundChannel)
    }

    @Test
    fun `with neither channel the preflight fails and the holder stays empty`() {
        capReached()

        assertNull(NotificationChannelSpecs.preflightForegroundChannel(context, "Test"))
        assertNull(NotificationChannelSpecs.verifiedForegroundChannel)
        assertNull(NotificationChannelSpecs.serviceForegroundChannel(context, "Test"))
    }

    // Once the preflight verified RUNNING_IN_BACKGROUND, a throwing lookup in the service
    // uses that id and never the unverified MESSAGE.
    @Test
    fun `a throwing service lookup falls back to the preflight id only`() {
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.preflightForegroundChannel(context, "Test"))
        every { nm.getNotificationChannel(any()) } throws RuntimeException("dead system")

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.serviceForegroundChannel(context, "Test"))

        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        assertNull(NotificationChannelSpecs.serviceForegroundChannel(context, "Test"))
    }

    @Test
    fun `a missing channel found by a working lookup is never replaced by the preflight id`() {
        NotificationChannelSpecs.preflightForegroundChannel(context, "Test")
        channels.clear()
        capReached()

        assertNull(NotificationChannelSpecs.serviceForegroundChannel(context, "Test"))
    }

    @Test
    fun `a MESSAGE fallback notification is silent and alerts once`() {
        val fallback = NotificationChannelSpecs.applyForegroundFallback(
            NotificationCompat.Builder(context, NotificationChannelIds.MESSAGE).setSmallIcon(android.R.drawable.ic_dialog_info),
            NotificationChannelIds.MESSAGE,
        ).build()
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, fallback.groupAlertBehavior)
        assertTrue(fallback.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)

        val normal = NotificationChannelSpecs.applyForegroundFallback(
            NotificationCompat.Builder(context, NotificationChannelIds.RUNNING_IN_BACKGROUND).setSmallIcon(android.R.drawable.ic_dialog_info),
            NotificationChannelIds.RUNNING_IN_BACKGROUND,
        ).build()
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, normal.groupAlertBehavior)
    }
}
