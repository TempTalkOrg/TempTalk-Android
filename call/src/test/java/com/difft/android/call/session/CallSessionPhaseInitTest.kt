package com.difft.android.call.session

import com.difft.android.call.cleanup.CallCleanupSteps
import com.difft.android.call.data.CallStatus
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.session.harness.PhaseBCollaboratorStubs
import com.difft.android.call.ui.barrage.CallBarrageFormatter
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import java.util.Collections

/**
 * Phase A (the session's start) of [CallSession], on the plain JVM, with the room setup (Phase B)
 * parked; Phase B rows live in [CallSessionPhaseBTest]. Also covers the session-side UI helpers and
 * the OnGoingCallStateManager projection writes.
 * Rows: T2-08, T2-09, T2-14, T3-11, T3-19, T5-05 (session half), T5-06, T5-14.
 */
class CallSessionPhaseInitTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        PhaseBCollaboratorStubs.install()
    }

    @After
    fun tearDown() {
        collectorScopes.forEach { it.cancel() }
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T2-08 Phase A registers camera, starts audio and observation, mirrors status, creates no room`() = runTest(testDispatcher) {
        val session = h.startWithPage()

        assertEquals(0, h.components.createRoomCalls)
        verify(exactly = 1) { h.components.audio.setup.start() }
        verify(exactly = 1) { h.contactorCacheManager.startParticipantObservation(session.mainScope) }
        assertEquals(1, h.components.cameraRegistrations)

        h.components.callStatus.value = CallStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(CallStatus.CONNECTED, h.ogsm.callStatus.value)
    }

    @Test
    fun `T2-14 cleanup before Phase B passes null for every lazy-gated collaborator and forces none`() {
        val captured = captureCleanupStepArgs()
        val session = h.start()

        session.end(CallSessionEndReason.EXIT_CLEAR)

        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
        assertNull(captured.getValue("roomEventDispatcher"))
        assertNull(captured.getValue("callTypeCoordinator"))
        assertNull(captured.getValue("networkQualityCoordinator"))
        assertNull(captured.getValue("screenShareFloatingSpeakerStateHolder"))
        assertEquals(0, h.components.createRoomCalls)
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().cancelJobs() }
    }

    @Test
    fun `T2-09 the session's end resets the mirrored status and later statuses are dropped`() = runTest(testDispatcher) {
        val session = h.startWithPage()
        h.components.callStatus.value = CallStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(CallStatus.CONNECTED, h.ogsm.callStatus.value)

        session.end(CallSessionEndReason.EXIT_CLEAR)
        assertEquals(CallStatus.UNKNOWN, h.ogsm.callStatus.value)
        h.components.callStatus.value = CallStatus.RECONNECTING
        advanceUntilIdle()

        assertEquals(CallStatus.UNKNOWN, h.ogsm.callStatus.value)
    }

    @Test
    fun `T2-09 after endPresence the live status mirror writes OGSM no more`() = runTest(testDispatcher) {
        val session = h.startWithPage()
        h.components.callStatus.value = CallStatus.CONNECTED
        advanceUntilIdle()

        session.graph.publisher.endPresence()
        h.components.callStatus.value = CallStatus.RECONNECTING
        advanceUntilIdle()

        assertTrue("the session job is still alive", session.scope.isActive)
        assertEquals("the reset value stays", CallStatus.UNKNOWN, h.ogsm.callStatus.value)
    }

    @Test
    fun `T5-06 the duration timer stamps callConnectedAt once and projects the calling time`() = runTest(testDispatcher) {
        val session = h.startWithPage()
        session.graph.onRoomIdAssigned("r1")

        session.startCallDurationTimer()
        val connectedAt = h.ogsm.callConnectedAt()
        advanceTimeBy(1_001)
        runCurrent()
        session.startCallDurationTimer() // a manual-switch re-connect: no re-stamp

        assertTrue(connectedAt > 0)
        assertEquals(connectedAt, h.ogsm.callConnectedAt())
        // The tick's text is computed from the wall clock, not from the virtual time advanced here,
        // so it is compared with the timer's own text rather than a fixed value.
        val tickText = session.graph.timerManager.callDurationText.value
        assertEquals("r1" to tickText, h.ogsm.callingTime.value)
        assertEquals(tickText, h.ogsm.getCallingTime("r1"))
        endSessionsBeforeDrain()
    }

    /**
     * The duration timer re-arms itself every virtual second on the session's main scope, which runs
     * on this test's scheduler; runTest drains that scheduler after the body, so the session must end
     * (cancelling its Job and the timer) inside the body or the drain never finishes.
     */
    private fun endSessionsBeforeDrain() = h.endAll()

    @Test
    fun `T5-14 status across a manual server switch is projected in order, callConnectedAt unchanged`() = runTest(testDispatcher) {
        val session = h.startWithPage()
        h.components.callStatus.value = CallStatus.CONNECTED
        advanceUntilIdle()
        session.startCallDurationTimer()
        val connectedAt = h.ogsm.callConnectedAt()

        val seen = mutableListOf<CallStatus>()
        backgroundScope.launch { h.ogsm.callStatus.collect { seen += it } }
        runCurrent()

        h.components.callStatus.value = CallStatus.SWITCHING_SERVER
        runCurrent()
        h.components.callStatus.value = CallStatus.CONNECTED
        runCurrent()

        assertEquals(listOf(CallStatus.CONNECTED, CallStatus.SWITCHING_SERVER, CallStatus.CONNECTED), seen)
        assertEquals(connectedAt, h.ogsm.callConnectedAt())
        endSessionsBeforeDrain()
    }

    @Test
    fun `T5-05 the remote-join step stops the ringtone, the vibration and the armed 1v1 timeout once each`() {
        val session = h.start()
        session.graph.onRoomIdAssigned("r1")
        val timeoutMonitor = CallSessionGraph::class.java.getDeclaredField("timeoutMonitor")
            .apply { isAccessible = true }.get(session.graph) as CallTimeoutMonitor
        timeoutMonitor.start1V1Timeout("r1")

        session.stopRingToneAndTimeoutCheck()

        verify(exactly = 1) { h.ringtone.stopRingTone() }
        verify(exactly = 1) { h.deps.callVibrationManager.stopVibration() }
        verify(exactly = 1) { h.callTimeoutManager.cancelCallWithTimeout("r1") }
    }

    /** Records the named arguments of every [CallCleanupSteps.build] call (last call wins), then runs the real build. */
    private fun captureCleanupStepArgs(): Map<String, Any?> {
        val captured = mutableMapOf<String, Any?>()
        val names = CallCleanupSteps::build.parameters.map { it.name!! }
        mockkObject(CallCleanupSteps)
        every {
            CallCleanupSteps.build(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers {
            names.zip(args).forEach { (n, v) -> captured[n] = v }
            callOriginal()
        }
        return captured
    }

    @Test
    fun `T3-11 the floating speaker reads the session's speakingEnabled flow`() {
        val local = mockk<LocalParticipant>(relaxed = true) {
            every { sid } returns Participant.Sid("local-sid")
            every { isSpeaking } returns true
        }
        every { h.components.room.localParticipant } returns local
        val session = h.start()

        h.uiWriter(session).setSpeakingEnabled(false)
        val holder = session.screenShareFloatingSpeaker as ScreenShareFloatingSpeakerStateHolder

        assertSame(session.uiState.speakingEnabled, holder.privateField("speakingEnabled"))
        assertFalse("a speaking participant is not highlighted while speaking is disabled", holder.uiState.value.isSpeaking)
    }

    @Test
    fun `T3-19 a barrage without identity or text emits nothing and launches no formatting`() {
        mockkObject(CallBarrageFormatter)
        val session = h.start()
        val events = collectEvents(session)
        val launchedBefore = session.mainScope.childCount()
        val anonymous = mockk<Participant>(relaxed = true) { every { identity } returns null }
        val named = mockk<Participant>(relaxed = true) { every { identity } returns Participant.Identity("bob.1") }

        session.showBarrage(anonymous, "x")
        session.showBarrage(named, "")

        assertEquals("no IO launch", launchedBefore, session.mainScope.childCount())
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { CallBarrageFormatter.format(any(), any(), any(), any(), any()) }
        assertTrue(events.isEmpty())
    }

    private val collectorScopes = mutableListOf<CoroutineScope>()

    /** Session UI events collected on the test Main dispatcher until `@After`. */
    private fun collectEvents(session: CallSession): List<CallSessionUiEvent> {
        val events = Collections.synchronizedList(mutableListOf<CallSessionUiEvent>())
        val scope = CoroutineScope(testDispatcher)
        scope.launch { session.uiState.events.collect { events += it } }
        collectorScopes += scope
        testDispatcher.scheduler.runCurrent()
        return events
    }

    private fun CoroutineScope.childCount() = coroutineContext[Job]!!.children.count()

    private fun Any.privateField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}

/** True once the `by lazy` property [name] of this graph has been forced. */
internal fun CallSessionGraph.lazyInitialized(name: String): Boolean {
    val field = CallSessionGraph::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
    return (field.get(this) as Lazy<*>).isInitialized()
}
