package com.difft.android.call.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.call.data.CallStatus
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

/**
 * #1354: every promotion of the ongoing-call [ForegroundService] builds its
 * notification on a channel verified at that promotion, and never calls `startForeground` without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class ForegroundServiceChannelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ogsm = OnGoingCallStateManager()
    private val controllerMock = mockk<OngoingCallServiceController>(relaxed = true)
    private val posted = mutableListOf<Notification>()
    private var service: ServiceController<ForegroundService>? = null

    @Before
    fun setUp() {
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        mockkStatic(ForegroundServiceStarter::class)
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), capture(posted), any()) } returns true
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<ForegroundService.ServiceEntryPoint>(relaxed = true) {
            every { onGoingCallStateManager() } returns ogsm
            every { ongoingCallServiceController() } returns controllerMock
        }
        every { EntryPoints.get(any(), ForegroundService.ServiceEntryPoint::class.java) } returns entryPoint
        ForegroundService.ownerSessionId = OWNER
    }

    @After
    fun tearDown() {
        service?.destroy()
        idle()
        ForegroundService.isServiceRunning = false
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        ForegroundService.pendingTypeUpdates = 0
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        unmockkAll()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): ServiceController<ForegroundService> =
        Robolectric.buildService(ForegroundService::class.java).also { service = it }.create().startCommand(0, 1)

    private fun installNm(nm: NotificationManager) {
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, nm)
    }

    private fun cappedNm(vararg existing: String): NotificationManager = mockk(relaxed = true) {
        every { getNotificationChannel(any()) } answers {
            firstArg<String>().takeIf { it in existing }?.let { NotificationChannel(it, it, NotificationManager.IMPORTANCE_HIGH) }
        }
        every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
    }

    // The starting and the ongoing-call notifications both sit on RUNNING_IN_BACKGROUND,
    // re-created before the promotion when missing.
    @Test
    fun `every promotion posts on a re-created running-in-background channel`() {
        OgsmTestState.setCallStatus(ogsm, CallStatus.CONNECTED)
        OgsmTestState.enterCall(ogsm, "r1")

        start()
        idle()

        assertEquals(2, posted.size)
        posted.forEach { assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, it.channelId) }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(nm.getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND))
    }

    @Test
    fun `at the channel cap the promotion falls back to MESSAGE, silent and alert-once`() {
        installNm(cappedNm(NotificationChannelIds.MESSAGE))

        start()
        idle()

        val notification = posted.single()
        assertEquals(NotificationChannelIds.MESSAGE, notification.channelId)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    // A throwing lookup uses the preflight id and never an unverified MESSAGE.
    @Test
    fun `a throwing lookup promotes on the preflight id`() {
        NotificationChannelSpecs.preflightForegroundChannel(app, "Test")
        installNm(mockk(relaxed = true) {
            every { getNotificationChannel(any()) } throws RuntimeException("dead system")
        })

        start()
        idle()

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, posted.single().channelId)
        assertTrue(ForegroundService.isServiceRunning)
    }

    @Test
    fun `with no channel the service stops without promoting and tells its session`() {
        installNm(cappedNm())

        val controller = start()
        idle()

        verify(exactly = 0) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(ForegroundService.isServiceRunning)
        verify { controllerMock.onPromoteFailed(OWNER) }
    }

    private companion object {
        const val OWNER = 41L
    }
}
