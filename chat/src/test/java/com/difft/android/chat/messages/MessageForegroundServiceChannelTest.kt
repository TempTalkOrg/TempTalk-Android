package com.difft.android.chat.messages

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.chat.util.ForegroundServiceUtil
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.difft.app.database.WCDBUpdateService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

/**
 * #1354: [MessageForegroundService] promotes only on a verified channel, and its
 * starter does not start it when no channel can carry the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class MessageForegroundServiceChannelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posted = slot<Notification>()
    private val counters = mockk<KeepAliveHealthCounters>(relaxed = true)

    @Before
    fun setUp() {
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        mockkStatic(ForegroundServiceStarter::class)
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), capture(posted), any()) } returns true
        mockkObject(WCDBUpdateService)
        every { WCDBUpdateService.updatingRooms() } returns Unit
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<MessageForegroundService.ServiceEntryPoint>(relaxed = true) {
            every { keepAliveHealthCounters() } returns counters
        }
        every { EntryPoints.get(any(), MessageForegroundService.ServiceEntryPoint::class.java) } returns entryPoint
    }

    @After
    fun tearDown() {
        unmockkAll()
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun realNm() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Replaces the notification service every context of the app resolves. */
    private fun installNm(nm: NotificationManager) {
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, nm)
    }

    /** A manager at the per-app channel cap: nothing can be created; [existing] channels are found. */
    private fun cappedNm(vararg existing: String): NotificationManager = mockk(relaxed = true) {
        every { getNotificationChannel(any()) } answers {
            firstArg<String>().takeIf { it in existing }?.let { NotificationChannel(it, it, NotificationManager.IMPORTANCE_HIGH) }
        }
        every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
    }

    private fun throwingNm(): NotificationManager = mockk(relaxed = true) {
        every { getNotificationChannel(any()) } throws RuntimeException("dead system")
        every { createNotificationChannel(any<NotificationChannel>()) } throws RuntimeException("dead system")
    }

    @Test
    fun `a missing running-in-background channel is re-created before the promotion`() {
        assertNull(realNm().getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND))

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create()
        idle()

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, posted.captured.channelId)
        assertNotNull(realNm().getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND))
        assertTrue(MessageForegroundService.isRunning)
        verify(exactly = 1) { counters.onServiceStarted() }
        controller.destroy()
    }

    @Test
    fun `at the channel cap the promotion falls back to MESSAGE, silent and alert-once`() {
        installNm(cappedNm(NotificationChannelIds.MESSAGE))

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create()
        idle()

        assertEquals(NotificationChannelIds.MESSAGE, posted.captured.channelId)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, posted.captured.groupAlertBehavior)
        assertTrue(posted.captured.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        controller.destroy()
    }

    // A sticky restart in a fresh process with no channel stops without startForeground.
    @Test
    fun `a sticky restart with no channel stops without promoting`() {
        installNm(cappedNm())

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create()
        val result = controller.get().onStartCommand(null, Service.START_FLAG_RETRY, 1)
        idle()

        verify(exactly = 0) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, result)
        // A service that stopped itself is neither running nor counted as started.
        assertFalse(MessageForegroundService.isRunning)
        verify(exactly = 0) { counters.onServiceStarted() }
        controller.destroy()
    }

    // The onCreate promotion uses the preflight id when the lookup throws, and stops
    // without promoting when there is none.
    @Test
    fun `an onCreate promotion with a throwing lookup uses the preflight id only`() {
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.preflightForegroundChannel(app, "Test"))
        installNm(throwingNm())

        val first = Robolectric.buildService(MessageForegroundService::class.java).create()
        idle()

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, posted.captured.channelId)
        first.destroy()

        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        val second = Robolectric.buildService(MessageForegroundService::class.java).create()
        idle()

        verify(exactly = 1) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertTrue(shadowOf(second.get()).isStoppedBySelf)
        second.destroy()
    }

    @Test
    fun `the starter does not start the service when no channel exists`() {
        installNm(cappedNm())

        assertFalse(ForegroundServiceUtil.start(app, Intent(app, MessageForegroundService::class.java)))

        assertNull(shadowOf(app).nextStartedService)
        assertNull(NotificationChannelSpecs.verifiedForegroundChannel)
    }

    @Test
    fun `the starter records the verified channel before starting the service`() {
        assertTrue(ForegroundServiceUtil.start(app, Intent(app, MessageForegroundService::class.java)))

        assertEquals(MessageForegroundService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.verifiedForegroundChannel)
    }
}
