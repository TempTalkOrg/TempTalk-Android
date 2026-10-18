package com.difft.android.call.session

import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.cleanup.CallCleanupSteps
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.session.harness.PhaseBCollaboratorStubs
import com.difft.android.call.ui.barrage.BarrageRenderable
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalTrackPublication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * The session's room setup (Phase B, `CallRoomWiring.kt`), launched once by [CallSession.start] on
 * the session's Default scope: wiring order, the once-only gate, the two abort points, the
 * resolved [CallSession.roomState], the end on failure, cancellation, and who releases the entry
 * point's wait-dialog claim. Rows: T2-10..T2-13, T3-20, T5-19, T5-27, T5-28.
 */
class CallSessionPhaseBTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    /** Phase B runs on the test scheduler: it runs when the test runs that scheduler. */
    private lateinit var h: CallSessionTestHarness
    private val extraHarnesses = mutableListOf<CallSessionTestHarness>()
    private val collectorScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher, default = testDispatcher)
        PhaseBCollaboratorStubs.install()
        mockkObject(CallWaitDialogUtil)
        every { CallWaitDialogUtil.dismiss(any<Int>()) } just Runs
    }

    @After
    fun tearDown() {
        collectorScopes.forEach { it.cancel() }
        (extraHarnesses + h).forEach { it.endAll() }
        unmockkAll()
    }

    @Test
    fun `Phase B runs once at start and resolves Ready with the created room`() {
        val session = h.startWithPage()
        assertEquals(CallRoomState.Pending, session.roomState.value)
        assertEquals("not inline in start", 0, h.components.createRoomCalls)

        testDispatcher.scheduler.runCurrent()

        assertEquals(CallRoomState.Ready(h.components.room), session.roomState.value)
        assertEquals(1, h.components.createRoomCalls)
        assertSame(session, h.holder.current.value)
        assertTrue(h.cleanupStarts.isEmpty())
    }

    @Test
    fun `T2-10 Phase B wires collaborators in order and starts network quality on main`() = runTest(testDispatcher) {
        val mainMarker = MainMarkingDispatcher(StandardTestDispatcher(testScheduler))
        Dispatchers.setMain(mainMarker)
        val h2 = CallSessionTestHarness(
            main = StandardTestDispatcher(testScheduler, name = "session-main"),
            default = StandardTestDispatcher(testScheduler, name = "session-default"),
        ).also { extraHarnesses += it }
        var startedOnMain: Boolean? = null
        every { PhaseBCollaboratorStubs.networkQuality.start() } answers { startedOnMain = MainMarkingDispatcher.onMain.get() }

        h2.startWithPage()
        advanceUntilIdle()

        verifyOrder {
            h2.components.roomCtl.createRoom()
            anyConstructed<CallTypeCoordinator>().start()
            anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents()
            anyConstructed<RoomEventDispatcher>().startCollectingParticipants()
            CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            anyConstructed<CallConnectionCoordinator>().observeManualSwitchReconnect(any(), any(), any(), any())
            anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any())
            anyConstructed<CallSessionStarter>().start()
        }
        assertEquals(true, startedOnMain)
        h2.endAll()
    }

    @Test
    fun `T2-11 abort point 1 resolves Failed, ends the session and leaves rtm initialized`() {
        h.components.releaseIntended = true
        val session = h.start()

        testDispatcher.scheduler.runCurrent()

        assertNotNull(session.rtm)
        verify(exactly = 0) { anyConstructed<CallTypeCoordinator>().start() }
        verify(exactly = 0) { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() }
        verify(exactly = 0) { CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { anyConstructed<CallSessionStarter>().start() }
        assertEquals(CallRoomState.Failed, session.roomState.value)
        assertEquals(listOf("roomFailed"), h.cleanupStarts.map { it.first })
        assertNull(h.holder.current.value)
    }

    @Test
    fun `T2-12 abort point 2 keeps collectors, never builds the critical alert dispatcher or connects, and fails`() {
        every {
            anyConstructed<CallConnectionCoordinator>().observeManualSwitchReconnect(any(), any(), any(), any())
        } answers {
            h.components.releaseIntended = true
            mockk(relaxed = true)
        }
        val session = h.startWithPage()

        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() }
        verify(exactly = 1) { anyConstructed<RoomEventDispatcher>().startCollectingParticipants() }
        verify(exactly = 1) { CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertFalse("CriticalAlertDispatcher must not be constructed", session.graph.lazyInitialized("criticalAlertDispatcher"))
        verify(exactly = 0) { anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any()) }
        verify(exactly = 0) { anyConstructed<CallSessionStarter>().start() }
        assertEquals(CallRoomState.Failed, session.roomState.value)
        assertEquals(listOf("roomFailed"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `T2-13 the wiring runs once for sequential and concurrent callers`() {
        val session = h.start()
        testDispatcher.scheduler.runCurrent()
        session.graph.startRoomDependentWiring()
        assertEquals(1, h.components.createRoomCalls)

        val parked = CallSessionTestHarness(testDispatcher).also { extraHarnesses += it }
        val concurrent = parked.start()
        val gate = CountDownLatch(1)
        val threads = List(8) { thread { gate.await(); concurrent.graph.startRoomDependentWiring() } }
        gate.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        assertEquals(1, parked.components.createRoomCalls)
        verify(exactly = 2) { anyConstructed<CallSessionStarter>().start() }
    }

    @Test
    fun `T5-19 a throwing wiring resolves Failed and the ROOM_FAILED end cleans up with lazy args null`() {
        every { h.components.roomCtl.createRoom() } throws IllegalStateException("createRoom failed")
        val captured = captureCleanupStepArgs()
        val session = h.start()

        testDispatcher.scheduler.runCurrent()

        assertEquals(CallRoomState.Failed, session.roomState.value)
        assertTrue(session.isEnded)
        assertEquals(listOf("roomFailed"), h.cleanupStarts.map { it.first })
        assertNull(captured.getValue("roomEventDispatcher"))
        assertNull(captured.getValue("callTypeCoordinator"))
        assertNull(captured.getValue("networkQualityCoordinator"))
        assertNull(captured.getValue("screenShareFloatingSpeakerStateHolder"))
    }

    @Test
    fun `a cancellation inside the wiring is not a failure`() {
        every { h.components.roomCtl.createRoom() } throws CancellationException("scope cancelled")
        val session = h.startWithPage()

        testDispatcher.scheduler.runCurrent()

        assertEquals(CallRoomState.Pending, session.roomState.value)
        assertSame(session, h.holder.current.value)
        assertTrue(h.cleanupStarts.isEmpty())
    }

    @Test
    fun `a session that ends before Phase B runs never sets up the room and ends only once`() {
        val session = h.start()

        session.end(CallSessionEndReason.EXIT_CLEAR)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0, h.components.createRoomCalls)
        assertEquals(CallRoomState.Pending, session.roomState.value)
        assertEquals(listOf("doExitClear"), h.cleanupStarts.map { it.first })
    }

    @Test
    fun `T5-27 lateinit rtm, media and active speakers are reachable after Phase B`() {
        val session = h.startWithPage()

        testDispatcher.scheduler.runCurrent()

        assertNotNull(session.rtm)
        assertNotNull(session.micEnabled)
        assertNotNull(session.deNoiseEnable)
        assertNotNull(session.voicePreset)
        assertNotNull(session.graph.activeSpeakers)
        assertEquals(h.components.room, session.room)
    }

    @Test
    fun `T5-28 Phase B runs on a Default thread, off the start's thread`() = runTest(testDispatcher) {
        val onDefault = CallSessionTestHarness(testDispatcher, default = Dispatchers.Default).also { extraHarnesses += it }
        val session = onDefault.startWithPage()

        val state = withContext(Dispatchers.Default) {
            withTimeout(AWAIT_TIMEOUT_MS) { session.roomState.first { it != CallRoomState.Pending } }
        }

        assertEquals(CallRoomState.Ready(onDefault.components.room), state)
        val createThread = onDefault.components.createRoomThread
        assertNotNull(createThread)
        assertTrue(createThread!!.name, createThread.name.startsWith("DefaultDispatcher-worker"))
        onDefault.endAll()
    }

    @Test
    fun `Phase B resolving with no page attached releases the entry wait-dialog claim`() {
        h.start(h.params(entryWaitToken = ENTRY_TOKEN))

        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { CallWaitDialogUtil.dismiss(ENTRY_TOKEN) }
    }

    @Test
    fun `with a page attached the entry claim is left to the page, which releases it`() {
        val session = h.startWithPage(h.params(entryWaitToken = ENTRY_TOKEN))

        testDispatcher.scheduler.runCurrent()
        verify(exactly = 0) { CallWaitDialogUtil.dismiss(any<Int>()) }

        session.releaseEntryWaitDialog()
        verify(exactly = 1) { CallWaitDialogUtil.dismiss(ENTRY_TOKEN) }
    }

    @Test
    fun `a page that goes away while Phase B runs leaves the entry claim to the session`() {
        val session = h.start(h.params(entryWaitToken = ENTRY_TOKEN))
        session.attachUi()
        session.detachUi()

        testDispatcher.scheduler.runCurrent()

        assertTrue(session.roomState.value is CallRoomState.Ready)
        verify(exactly = 1) { CallWaitDialogUtil.dismiss(ENTRY_TOKEN) }
    }

    @Test
    fun `T3-20 enabling the mic through the media controller emits one open-mic barrage`() {
        mockkObject(PermissionUtil, ApplicationHelper, ResUtils)
        every { PermissionUtil.arePermissionsGranted(any(), any()) } returns true
        every { ApplicationHelper.instance } returns mockk(relaxed = true)
        every { ResUtils.getString(R.string.call_barrage_message_open_mic) } returns "mic on"
        val local = mockk<LocalParticipant>(relaxed = true) {
            every { identity } returns Participant.Identity("self-uid.1")
            every { audioTrackPublications } returns listOf(mockk<LocalTrackPublication>(relaxed = true) to null)
        }
        every { h.components.room.localParticipant } returns local
        val session = h.start()
        val events = collectEvents(session)
        testDispatcher.scheduler.runCurrent()

        session.setMicEnabled(true)
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(AWAIT_POLL_MS)
        }

        val barrage = events.single() as CallSessionUiEvent.Barrage
        assertEquals("mic on", (barrage.renderable as BarrageRenderable.Default).message.message)
    }

    /** Session UI events collected on the test Main dispatcher until `@After`. */
    private fun collectEvents(session: CallSession): List<CallSessionUiEvent> {
        val events = Collections.synchronizedList(mutableListOf<CallSessionUiEvent>())
        val scope = CoroutineScope(testDispatcher)
        scope.launch { session.uiState.events.collect { events += it } }
        collectorScopes += scope
        return events
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

    private companion object {
        const val ENTRY_TOKEN = 7
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 5L
    }

    /** Dispatches through [delegate] and marks the running block, so a stub can tell it ran via `Dispatchers.Main`. */
    private class MainMarkingDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, Runnable {
                onMain.set(true)
                try { block.run() } finally { onMain.set(false) }
            })
        }

        companion object {
            val onMain: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }
}
