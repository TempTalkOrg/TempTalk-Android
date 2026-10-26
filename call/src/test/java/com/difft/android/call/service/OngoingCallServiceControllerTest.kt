package com.difft.android.call.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.call.LCallToChatController
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import util.AppForegroundObserver

/**
 * [OngoingCallServiceController]: the ongoing-call service is started for a session id and stopped
 * only by that session; a start from the background is deferred to the page's `onResume` or the next
 * foreground transition; a stop never brings the service down while a type-update start is on its
 * way; every platform call is caught. Robolectric for the real `Intent` the starts carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class OngoingCallServiceControllerTest {

    private val app = mockk<Application>(relaxed = true)
    private val callToChat = mockk<LCallToChatController>(relaxed = true)
    private val controller = OngoingCallServiceController(app, callToChat)
    private val starts = mutableListOf<Intent>()
    private val listeners = mutableListOf<AppForegroundObserver.Listener>()
    private val nm = mockk<NotificationManager>(relaxed = true)

    @Before
    fun setUp() {
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        every { app.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND) } returns
            NotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND, "Running in background", NotificationManager.IMPORTANCE_LOW)
        every { callToChat.isAppForegrounded() } returns true
        every { callToChat.startForegroundService(any(), capture(starts), any()) } returns true
        every { app.startForegroundService(any()) } returns ComponentName("pkg", ForegroundService::class.java.name)
        mockkStatic(AppForegroundObserver::class)
        every { AppForegroundObserver.addListener(any()) } answers { listeners += firstArg<AppForegroundObserver.Listener>() }
        every { AppForegroundObserver.removeListener(any()) } answers { listeners -= firstArg<AppForegroundObserver.Listener>() }
    }

    @After
    fun tearDown() {
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        ForegroundService.isServiceRunning = false
        ForegroundService.pendingTypeUpdates = 0
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        unmockkAll()
    }

    // #1354: with no channel for the notification, no start is ever issued.
    @Test
    fun `no start is issued while no foreground channel exists`() {
        every { nm.getNotificationChannel(any()) } returns null
        every { nm.createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")

        controller.start(SESSION)
        controller.ensureStarted(SESSION)
        ForegroundService.isServiceRunning = true
        controller.updateServiceType(SESSION)

        verify(exactly = 0) { callToChat.startForegroundService(any(), any(), any()) }
        verify(exactly = 0) { app.startForegroundService(any()) }
        assertEquals(0, ForegroundService.pendingTypeUpdates)
        assertNull(NotificationChannelSpecs.verifiedForegroundChannel)
    }

    // A failed preflight keeps the start unissued with the retry armed.
    @Test
    fun `a foreground start without a channel is retried on the next foreground`() {
        every { nm.getNotificationChannel(any()) } returns null
        every { nm.createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")

        controller.start(SESSION)
        assertEquals(0, starts.size)
        assertEquals(1, listeners.size)

        every { nm.getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND) } returns
            NotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND, "Running in background", NotificationManager.IMPORTANCE_LOW)
        listeners.single().onForeground()

        assertEquals(1, starts.size)
    }

    // The starter's own preflight failing is not a silent "issued".
    @Test
    fun `a start the starter declines stays unissued and is retried`() {
        every { callToChat.startForegroundService(any(), any(), any()) } returns false

        controller.start(SESSION)
        assertEquals(1, listeners.size)
        verify(exactly = 1) { callToChat.startForegroundService(any(), any(), any()) }

        every { callToChat.startForegroundService(any(), capture(starts), any()) } returns true
        controller.ensureStarted(SESSION)
        assertEquals(1, starts.size)
        assertEquals(0, listeners.size)
    }

    // A start the starter deferred and then could not issue re-arms the retry.
    @Test
    fun `a deferred start that fails later re-arms the retry for the current session only`() {
        val onDeferredFailure = slot<() -> Unit>()
        every { callToChat.startForegroundService(any(), any(), capture(onDeferredFailure)) } returns true

        controller.start(SESSION)
        assertEquals(0, listeners.size)
        onDeferredFailure.captured()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, listeners.size)

        controller.ensureStarted(SESSION)
        verify(exactly = 2) { callToChat.startForegroundService(any(), any(), any()) }
        assertEquals(0, listeners.size)

        // Stale: another session owns the service by the time the deferred failure lands.
        ForegroundService.ownerSessionId = OTHER
        onDeferredFailure.captured()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, listeners.size)

        // Or the service is already running for this session.
        ForegroundService.ownerSessionId = SESSION
        ForegroundService.isServiceRunning = true
        onDeferredFailure.captured()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, listeners.size)
    }

    @Test
    fun `a start records the verified channel for the service`() {
        controller.start(SESSION)

        assertEquals(1, starts.size)
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.verifiedForegroundChannel)
    }

    @Test
    fun `a start in the foreground issues one start carrying the session id`() {
        controller.start(SESSION)

        assertEquals(SESSION, ForegroundService.ownerSessionId)
        assertEquals(1, starts.size)
        assertEquals(ForegroundService::class.java.name, starts.single().component?.className)
        assertEquals(SESSION, starts.single().getLongExtra(ForegroundService.EXTRA_SESSION_ID, -1))

        controller.ensureStarted(SESSION)
        assertEquals("issued once", 1, starts.size)
    }

    @Test
    fun `the owner's stop stops the running service once and clears the owner`() {
        controller.start(SESSION)
        ForegroundService.isServiceRunning = true
        val stopped = slot<Intent>()
        every { app.stopService(capture(stopped)) } returns true

        controller.stop(SESSION, "test")
        controller.stop(SESSION, "test")

        verify(exactly = 1) { app.stopService(any()) }
        assertEquals(ForegroundService::class.java.name, stopped.captured.component?.className)
        assertEquals(ForegroundService.NO_OWNER, ForegroundService.ownerSessionId)
    }

    @Test
    fun `another session's stop is skipped and the owner keeps the service`() {
        controller.start(SESSION)
        ForegroundService.isServiceRunning = true

        controller.stop(OTHER, "late end")

        verify(exactly = 0) { app.stopService(any()) }
        assertEquals(SESSION, ForegroundService.ownerSessionId)
    }

    @Test
    fun `a stop before the service promoted is left to its self-stop`() {
        controller.start(SESSION)

        controller.stop(SESSION, "test")

        verify(exactly = 0) { app.stopService(any()) }
        assertEquals(ForegroundService.NO_OWNER, ForegroundService.ownerSessionId)
    }

    @Test
    fun `in the background the start is deferred and ensureStarted issues it once`() {
        every { callToChat.isAppForegrounded() } returns false

        controller.start(SESSION)
        assertEquals(0, starts.size)
        assertEquals(SESSION, ForegroundService.ownerSessionId)

        controller.ensureStarted(OTHER)
        assertEquals("another session issues nothing", 0, starts.size)
        controller.ensureStarted(SESSION)
        controller.ensureStarted(SESSION)

        assertEquals(1, starts.size)
        assertEquals("the issued start disarms the foreground retry", 0, listeners.size)
    }

    @Test
    fun `a deferred start is issued by the next foreground transition, which then stops listening`() {
        every { callToChat.isAppForegrounded() } returns false
        controller.start(SESSION)
        val listener = listeners.single()

        listener.onForeground()

        assertEquals(1, starts.size)
        verify(exactly = 1) { AppForegroundObserver.removeListener(listener) }
        assertEquals(0, listeners.size)
    }

    @Test
    fun `the foreground state replayed while arming does not issue the start`() {
        every { callToChat.isAppForegrounded() } returns false
        every { AppForegroundObserver.addListener(any()) } answers {
            val listener = firstArg<AppForegroundObserver.Listener>()
            listeners += listener
            listener.onForeground()
        }

        controller.start(SESSION)

        assertEquals(0, starts.size)
        listeners.single().onForeground()
        assertEquals("a later transition does", 1, starts.size)
    }

    @Test
    fun `the owner's stop disarms a deferred start`() {
        every { callToChat.isAppForegrounded() } returns false
        controller.start(SESSION)
        val listener = listeners.single()

        controller.stop(SESSION, "test")
        listener.onForeground()
        controller.ensureStarted(SESSION)

        assertEquals(0, starts.size)
        assertEquals(0, listeners.size)
    }

    @Test
    fun `a failed promotion re-arms the start for the owner only`() {
        controller.start(SESSION)
        assertEquals(1, starts.size)

        controller.onPromoteFailed(OTHER)
        controller.onPromoteFailed(ForegroundService.NO_OWNER)
        controller.ensureStarted(SESSION)
        assertEquals("not re-armed by another session", 1, starts.size)
        assertEquals(0, listeners.size)

        controller.onPromoteFailed(SESSION)
        assertEquals("waits for a foreground transition or the page", 1, listeners.size)
        controller.ensureStarted(SESSION)

        assertEquals(2, starts.size)
        assertEquals(0, listeners.size)
    }

    @Test
    fun `a stop while a type-update start is pending is left to the self-stop`() {
        controller.start(SESSION)
        ForegroundService.isServiceRunning = true
        controller.updateServiceType(SESSION)
        assertEquals(1, ForegroundService.pendingTypeUpdates)

        controller.stop(SESSION, "test")

        verify(exactly = 0) { app.stopService(any()) }
        assertEquals(ForegroundService.NO_OWNER, ForegroundService.ownerSessionId)
    }

    @Test
    fun `a type update is issued directly with the update action, only by the owner of a running service`() {
        val updates = mutableListOf<Intent>()
        every { app.startForegroundService(capture(updates)) } returns ComponentName("pkg", ForegroundService::class.java.name)
        controller.start(SESSION)

        controller.updateServiceType(SESSION)
        assertEquals("not running yet", 0, updates.size)

        ForegroundService.isServiceRunning = true
        controller.updateServiceType(OTHER)
        assertEquals("not the owner", 0, updates.size)

        controller.updateServiceType(SESSION)

        assertEquals(ForegroundService.ACTION_UPDATE_SERVICE_TYPE, updates.single().action)
        assertEquals(SESSION, updates.single().getLongExtra(ForegroundService.EXTRA_SESSION_ID, -1))
        assertEquals(1, ForegroundService.pendingTypeUpdates)
        assertEquals("never through the deferring wrapper", 1, starts.size)
    }

    @Test
    fun `an update start that throws or does not reach the service is not counted, and the count stays at zero`() {
        controller.start(SESSION)
        ForegroundService.isServiceRunning = true
        every { app.startForegroundService(any()) } throws IllegalStateException("not allowed")

        controller.updateServiceType(SESSION)
        assertEquals(0, ForegroundService.pendingTypeUpdates)

        every { app.startForegroundService(any()) } returns null
        controller.updateServiceType(SESSION)
        assertEquals(0, ForegroundService.pendingTypeUpdates)
    }

    @Test
    fun `start and stop failures are logged, not thrown`() {
        every { callToChat.startForegroundService(any(), any(), any()) } throws IllegalStateException("start")
        every { app.stopService(any()) } throws SecurityException("stop")

        controller.start(SESSION)
        assertEquals(SESSION, ForegroundService.ownerSessionId)

        every { callToChat.startForegroundService(any(), capture(starts), any()) } returns true
        controller.ensureStarted(SESSION)
        assertEquals("a failed start can be issued again", 1, starts.size)

        ForegroundService.isServiceRunning = true
        controller.stop(SESSION, "test")
        assertEquals(ForegroundService.NO_OWNER, ForegroundService.ownerSessionId)
        assertNull(listeners.firstOrNull())
    }

    private companion object {
        const val SESSION = 7L
        const val OTHER = 8L
    }
}
