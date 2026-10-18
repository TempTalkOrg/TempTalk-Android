package com.difft.android.call.service

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.call.CallIntent
import com.difft.android.call.data.CallStatus
import com.difft.android.call.receiver.CallNotificationActionReceiver
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionDispatchers
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.session.CallSessionParams
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Out-of-page events around the ongoing-call service.
 *
 * T4-10: the ongoing-call notification's hang-up is an immutable, explicit-component broadcast
 * `PendingIntent` to the manifest-declared, non-exported [CallNotificationActionReceiver], whose
 * `onReceive` hangs the call up through [CallSessionCommands]. AOSP grants an own-uid
 * `PendingIntent` broadcast to a non-exported receiver of the same app
 * (`ActivityManager.checkComponentPermission` checks `UserHandle.isSameApp` before `exported`).
 *
 * T4-11: a task swipe (`onTaskRemoved`), with an `ACTION_MAIN` root intent (the main app task),
 * another one or none, leaves the live call alone: nothing reaches it through the real facade and
 * no broadcast is sent.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallNotificationActionIntegrationTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ogsm = OnGoingCallStateManager()
    private lateinit var commands: CallSessionCommands
    private var controller: ServiceController<ForegroundService>? = null

    @Before
    fun setUp() {
        mockkStatic(ForegroundServiceStarter::class)
        mockkStatic(EntryPoints::class)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        ForegroundService.isServiceRunning = false
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        unmockkAll()
    }

    @Test
    fun `T4-10 the notification hang-up targets the non-exported receiver, which hangs up once`() {
        commands = mockk(relaxed = true)
        stubEntryPoints()
        val posted = slot<Notification>()
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), capture(posted), any()) } returns true
        OgsmTestState.setCallStatus(ogsm, CallStatus.CONNECTED)
        OgsmTestState.enterCall(ogsm, "r1")

        startService()
        val pi = hangUpIntent(posted.captured)

        val shadow = shadowOf(pi)
        assertTrue(shadow.isBroadcast)
        val saved = shadow.savedIntent
        val component = ComponentName(app, CallNotificationActionReceiver::class.java)
        assertEquals(component, saved.component)
        assertEquals(CallNotificationActionReceiver.ACTION_HANG_UP, saved.action)
        assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertFalse("not exported", app.packageManager.getReceiverInfo(component, 0).exported)

        CallNotificationActionReceiver().onReceive(app, Intent(saved).setAction("other"))
        verify(exactly = 0) { commands.hangup(any()) }

        CallNotificationActionReceiver().onReceive(app, saved)
        verify(exactly = 1) { commands.hangup(HangupReason.NOTIFICATION) }
    }

    @Test
    fun `T4-11 swiping the main app task away leaves the call and the commands untouched`() {
        val session = liveSession()
        stubEntryPoints()
        val service = createService()

        service.onTaskRemoved(Intent(Intent.ACTION_MAIN))
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { session.hangup(any()) }
        verify(exactly = 0) { session.onRemoteControl(any(), any()) }
        assertTrue("no broadcast", shadowOf(app).broadcastIntents.isEmpty())
    }

    @Test
    fun `T4-11 a task swipe with any other or no root intent leaves the call and the commands untouched`() {
        val session = liveSession()
        stubEntryPoints()
        val service = createService()

        service.onTaskRemoved(Intent(CallIntent.Action.START_CALL.code))
        service.onTaskRemoved(null)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { session.hangup(any()) }
        verify(exactly = 0) { session.onRemoteControl(any(), any()) }
        assertTrue("no broadcast", shadowOf(app).broadcastIntents.isEmpty())
    }

    /** A holder whose current session is a recording mock; [commands] is the real facade on main. */
    private fun liveSession(): CallSession {
        val session = mockk<CallSession>(relaxed = true) { every { start() } returns true }
        val holder = CallSessionHolder(mockk { every { create(any(), any()) } returns session })
        holder.start(CallSessionParams(false, CallIntentFixture.build(), CallConfig(), CallRole.CALLER, VoicePreset.ORIGINAL))
        commands = CallSessionCommands(holder, CallSessionDispatchers(Dispatchers.Main.immediate, Dispatchers.Main.immediate))
        return session
    }

    private fun stubEntryPoints() {
        val serviceEntryPoint = mockk<ForegroundService.ServiceEntryPoint> {
            every { onGoingCallStateManager() } returns ogsm
        }
        val receiverEntryPoint = mockk<CallNotificationActionReceiver.ReceiverEntryPoint> {
            every { callSessionCommands() } returns commands
        }
        every { EntryPoints.get(any(), ForegroundService.ServiceEntryPoint::class.java) } returns serviceEntryPoint
        every { EntryPoints.get(any(), CallNotificationActionReceiver.ReceiverEntryPoint::class.java) } returns receiverEntryPoint
    }

    private fun createService(): ForegroundService =
        Robolectric.buildService(ForegroundService::class.java).also { controller = it }.create().get()

    /** Created, foreground-started for a live session; the state collector then posts the ongoing-call notification. */
    private fun startService() {
        ForegroundService.ownerSessionId = 1L
        Robolectric.buildService(ForegroundService::class.java).also { controller = it }.create().startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** CallStyle keeps its hang-up intent in the extras; the vendor-template path adds it as an action. */
    private fun hangUpIntent(notification: Notification): PendingIntent {
        @Suppress("DEPRECATION")
        val fromStyle = notification.extras.getParcelable<PendingIntent>(Notification.EXTRA_HANG_UP_INTENT)
        val pi = fromStyle ?: notification.actions?.firstOrNull()?.actionIntent
        assertNotNull("ongoing notification carries a hang-up intent", pi)
        return pi!!
    }
}
