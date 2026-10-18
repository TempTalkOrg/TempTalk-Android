package com.difft.android.call.session

import androidx.lifecycle.ViewModelStore
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.call.CallIntent
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallViewModel
import com.difft.android.call.LCallViewModelFixture
import com.difft.android.call.R
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a [CallSession] ends: the call-level teardown (screen-off lock, sounds, participant
 * observation, ongoing-call service, presence, calling flag) runs once, before the holder is
 * cleared, each action isolated; the clear always happens; the session `Job` lives until the last
 * cleanup step; a destroyed page leaves the call running, and the last page going away ends a
 * session whose finish no page rendered; the process-wide slots are claimed at start; a start that
 * throws unwinds through the same end; an app unlock clears the app-lock flag, page or not, until the
 * session ends. Robolectric for the toast path's resources; the re-attach
 * rows that need a resolved room live in [CallSessionReattachTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionLifetimeTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val vibration = mockk<CallVibrationManager>(relaxed = true)
    private val callData = mockk<CallDataManager>(relaxed = true)
    private val statistics = mockk<CallStatisticsLogManager>(relaxed = true)
    private val toasts = mutableListOf<String>()

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        every { h.deps.callVibrationManager } returns vibration
        every { h.deps.callDataManager } returns callData
        every { h.deps.statisticsLogManager } returns statistics
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<String>()) } answers { toasts += firstArg<String>() }
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `the call-level teardown runs in order, once, before the holder clears`() {
        val session = h.start()
        session.graph.onRoomIdAssigned(ROOM_ID)
        val currentAt = mutableMapOf<String, CallSession?>()
        every { h.components.proximity.release() } answers { currentAt["proximity"] = h.holder.current.value }
        every { h.ongoingCallService.stop(session.id, any()) } answers { currentAt["service"] = h.holder.current.value }
        every { callData.updateCallingState(ROOM_ID, false) } answers { currentAt["callingState"] = h.holder.current.value }

        session.end(CallSessionEndReason.EXIT_CLEAR)
        session.end(CallSessionEndReason.NO_UI)

        verifyOrder {
            h.components.proximity.release()
            vibration.stopVibration(any())
            h.ringtone.stopRingTone(any())
            h.contactorCacheManager.stopParticipantObservation()
            h.contactorCacheManager.clearContactorCache()
            h.ongoingCallService.stop(session.id, any())
            callData.updateCallingState(ROOM_ID, false)
        }
        verify(exactly = 1) { h.components.proximity.release() }
        verify(exactly = 1) { h.ringtone.stopRingTone(any()) }
        verify(exactly = 1) { h.contactorCacheManager.stopParticipantObservation() }
        assertEquals(mapOf("proximity" to session, "service" to session, "callingState" to session), currentAt)
        assertFalse("presence ended", h.ogsm.isInCalling())
        assertNull(h.holder.current.value)
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `a throwing teardown action stops neither the later actions nor the clear, and a second end is a no-op`() {
        val session = h.start()
        every { h.ringtone.stopRingTone(any()) } throws IllegalStateException("player gone")

        session.end(CallSessionEndReason.EXIT_CLEAR)

        verify(exactly = 1) { h.contactorCacheManager.stopParticipantObservation() }
        verify(exactly = 1) { h.ongoingCallService.stop(session.id, any()) }
        assertFalse(h.ogsm.isInCalling())
        assertNull(h.holder.current.value)

        session.end(CallSessionEndReason.NO_UI)

        verify(exactly = 1) { h.ringtone.stopRingTone(any()) }
        verify(exactly = 1) { h.ongoingCallService.stop(any(), any()) }
        assertEquals(1, h.cleanupStarts.size)
    }

    @Test
    fun `a cleanup executor that fails to start cancels the job inline and the holder still clears`() {
        mockkConstructor(ScreenSharePreWarmer::class)
        every { anyConstructed<ScreenSharePreWarmer>().cleanupAll() } returns Unit
        every { anyConstructed<CallCleanupExecutor>().start(any(), any()) } throws
            IllegalStateException("no scope")
        val session = h.start()

        session.end(CallSessionEndReason.EXIT_CLEAR)

        assertFalse(session.scope.isActive)
        verify(exactly = 1) { anyConstructed<ScreenSharePreWarmer>().cleanupAll() }
        assertNull(h.holder.current.value)
    }

    @Test
    fun `only the last cleanup step cancels the session job`() = runTest(testDispatcher) {
        mockkConstructor(ScreenSharePreWarmer::class)
        every { anyConstructed<ScreenSharePreWarmer>().cleanupAll() } returns Unit
        val session = h.start()

        session.end(CallSessionEndReason.NO_UI)
        val steps = h.cleanupStarts.single().second
        assertEquals("cancelSessionScope", steps.last().name)

        steps.dropLast(1).forEach { step -> runCatching { step.action() } }
        assertTrue("alive until the last step", session.scope.isActive)
        verify(exactly = 0) { anyConstructed<ScreenSharePreWarmer>().cleanupAll() }

        steps.last().action()

        assertFalse(session.scope.isActive)
        verify(exactly = 1) { anyConstructed<ScreenSharePreWarmer>().cleanupAll() }
    }

    @Test
    fun `a remote end with no page stops the service, plays the tone, tips once and ends the session`() {
        val session = h.start()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)

        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()

        verifyOrder {
            h.ongoingCallService.stop(session.id, any())
            h.ringtone.playHangupRingTone()
        }
        assertEquals(listOf(ResUtils.getString(R.string.call_callee_action_hangup)), toasts)
        assertNull(h.holder.current.value)
        assertFalse(h.ogsm.isInCalling())
    }

    @Test
    fun `a destroyed call page leaves the call running`() {
        val vm = LCallViewModelFixture.build(h)
        val session = h.sessions.single()

        destroy(vm)
        testDispatcher.scheduler.runCurrent()

        assertSame(session, h.holder.current.value)
        assertFalse(session.isEnded)
        assertTrue(h.ogsm.isInCalling())
        verify(exactly = 0) { h.ongoingCallService.stop(any(), any()) }
        verify(exactly = 0) { h.ringtone.stopRingTone(any()) }
        verify(exactly = 0) { vibration.stopVibration(any()) }
        verify(exactly = 0) { h.contactorCacheManager.stopParticipantObservation() }
        assertTrue(h.cleanupStarts.isEmpty())
        assertEquals(0, session.graph.endController.uiAttachCount)
    }

    @Test
    fun `a configuration change keeps the page attached and the recreated page gets the sticky finish`() {
        // A recreation keeps the view model (no onCleared), so nothing detaches.
        val vm = LCallViewModelFixture.build(h)
        val session = h.sessions.single()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)

        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, session.graph.endController.uiAttachCount)
        assertSame("the page renders the end", session, h.holder.current.value)
        assertEquals(CallFinishRequest.remote(CallActionType.HANGUP), vm.finishRequest.value)
        assertTrue("the page shows the tip", toasts.isEmpty())
    }

    @Test
    fun `a remote end after the page was destroyed ends the call without a page`() {
        val vm = LCallViewModelFixture.build(h)
        val session = h.sessions.single()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        destroy(vm)

        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()

        verifyOrder {
            h.ongoingCallService.stop(session.id, any())
            h.ringtone.playHangupRingTone()
        }
        assertEquals(listOf(ResUtils.getString(R.string.call_callee_action_hangup)), toasts)
        assertNull(h.holder.current.value)
        assertFalse(h.ogsm.isInCalling())
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `the last page going away with a finish it never rendered ends the session without a page`() {
        val session = h.start()
        OgsmTestState.enterCall(h.ogsm, ROOM_ID, session.id)
        session.attachUi()
        session.attachUi()
        session.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        testDispatcher.scheduler.runCurrent()
        assertSame("a page is attached: it renders the finish", session, h.holder.current.value)

        session.detachUi()
        testDispatcher.scheduler.runCurrent()
        assertSame("another page is still attached", session, h.holder.current.value)
        assertTrue(toasts.isEmpty())

        session.detachUi()
        testDispatcher.scheduler.runCurrent()

        assertNull(h.holder.current.value)
        assertEquals(listOf(ResUtils.getString(R.string.call_callee_action_hangup)), toasts)
        assertEquals(listOf("noUi"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `the last page going away with no finish pending leaves the session running`() {
        val session = h.start()
        session.attachUi()

        session.detachUi()
        testDispatcher.scheduler.runCurrent()

        assertSame(session, h.holder.current.value)
        assertTrue(h.cleanupStarts.isEmpty())
    }

    @Test
    fun `a start that throws in Phase A unwinds what ran and leaves the holder free for the next call`() {
        every { h.components.proximity.start() } throws IllegalStateException("no sensor")

        val started = h.holder.start(h.params())

        assertNull(started)
        val failed = h.sessions.single()
        assertTrue(failed.isEnded)
        assertNull(h.holder.current.value)
        assertFalse("the presence Phase A began is ended", h.ogsm.isInCalling())
        verify(exactly = 0) { h.ongoingCallService.start(any()) }
        verify(exactly = 1) { h.ongoingCallService.stop(failed.id, any()) }
        assertEquals(listOf("startFailed"), h.cleanupStarts.map { it.first })

        every { h.components.proximity.start() } returns Unit
        val next = h.start()

        assertSame(next, h.holder.current.value)
        assertTrue(h.ogsm.isInCalling())
    }

    @Test
    fun `a 1v1 start shows its peer as awaiting from the start, a join does not`() {
        val start = h.start(h.params(action = CallIntent.Action.START_CALL, type = CallType.ONE_ON_ONE.type))
        assertEquals(listOf("conv-1"), start.participantManager.awaitingJoinInvitees.value)
        start.end(CallSessionEndReason.EXIT_CLEAR)

        val join = h.start(h.params(action = CallIntent.Action.JOIN_CALL, role = CallRole.CALLEE))

        assertTrue(join.participantManager.awaitingJoinInvitees.value.isEmpty())
    }

    @Test
    fun `a start claims the process-wide slots for the session and starts the screen-off lock`() {
        LCallEngine.setConnectedServerUrl("wss://older-call")

        val session = h.start()

        verify(exactly = 1) { statistics.claimRoom(session.id) }
        assertNull(LCallEngine.serverUrlConnected.value)
        verify(exactly = 1) { h.components.proximity.start() }
        assertTrue(h.ogsm.isInCalling())
    }

    @Test
    fun `an app unlock with no page clears the app-lock flag, and the session's end removes its listener`() {
        val session = h.start(h.params(needAppLock = true))
        assertTrue(h.ogsm.needAppLock)

        // The session holds its listener: the registry keeps only a weak reference.
        System.gc()
        AppLockCallbackManager.notifyUnlockSuccess()
        assertFalse("unlocked with no call page", h.ogsm.needAppLock)

        session.end(CallSessionEndReason.EXIT_CLEAR)
        assertTrue("the session end restores the flag", h.ogsm.needAppLock)
        AppLockCallbackManager.notifyUnlockSuccess()
        assertTrue("the ended session's listener is gone", h.ogsm.needAppLock)
    }

    /** What a non-configuration destroy does to the page's view model: its store is cleared. */
    private fun destroy(vm: LCallViewModel) = ViewModelStore().apply {
        put("call-vm", vm)
        clear()
    }

    private companion object {
        const val ROOM_ID = "r1"
    }
}
