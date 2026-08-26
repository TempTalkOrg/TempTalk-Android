package com.difft.android.call.handler

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the two [RoomEventDispatcher] methods the weak-network feature rewires
 * (cases C10-C12 of the test contract).
 *
 * `onConnectionQualityChanged` is a shared entry point: besides feeding the new verdict unit it also
 * drives the post-call rating trigger (`feedbackBinder.currentCallNetworkPoor`). Its input set (local
 * events only), its classifier (`quality !in {EXCELLENT, GOOD}`, which counts UNKNOWN as poor) and its
 * last-reading-wins write semantics must stay exactly as they were — the render-side level mapping
 * deliberately classifies UNKNOWN differently, and aligning the two would silently change how often
 * the rating sheet appears.
 *
 * C12 covers the removal of the old 60 s debounced toast chain: a weak network must no longer push an
 * error into `roomCtl.collectError` at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomEventDispatcherConnectionQualityTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var room: Room
    private lateinit var roomCtl: CallRoomController
    private lateinit var coordinator: CallConnectionCoordinator
    private lateinit var timeoutMonitor: CallTimeoutMonitor
    private lateinit var networkQuality: NetworkQualityCoordinator

    /** Every host.onNetworkPoorStateChanged(poor) value, in order. */
    private val networkPoorCalls = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        room = mockk(relaxed = true)
        roomCtl = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        timeoutMonitor = mockk(relaxed = true)
        networkQuality = mockk(relaxed = true)
        every { coordinator.isRetryUrlConnecting } returns false
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
        every { room.state } returns Room.State.CONNECTED
    }

    @After
    fun tearDown() {
        unmockkAll()
        networkPoorCalls.clear()
    }

    private fun buildDispatcher(): RoomEventDispatcher {
        val host = RoomEventHost(
            showBarrageFn = { _, _, _ -> },
            setMicEnabledFn = { _, _, _ -> },
            setCameraEnabledFn = {},
            resetNoBodySpeakCheckFn = {},
            sendHangUpBroadcastFn = {},
            stopRingToneAndTimeoutCheckFn = {},
            resolveCallTypeFn = {},
            handleConnectedStateFn = {},
            startCallDurationTimerFn = {},
            onFeedbackIdentityResolvedFn = { _, _, _ -> },
            onNetworkPoorStateChangedFn = { poor -> networkPoorCalls.add(poor) },
            getCurrentCallTypeFn = { CallType.ONE_ON_ONE.type },
            getCurrentRoomIdFn = { "room-1" },
        )
        return RoomEventDispatcher(
            scope = CoroutineScope(dispatcherRule.testDispatcher),
            room = room,
            roomCtl = roomCtl,
            rtm = mockk(relaxed = true),
            connectionCoordinator = coordinator,
            callUiController = mockk(relaxed = true),
            participantManager = mockk(relaxed = true),
            screenSharePreWarmer = mockk(relaxed = true),
            timeoutMonitor = timeoutMonitor,
            timerManager = mockk(relaxed = true),
            speakerState = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            statisticsLogManager = mockk(relaxed = true),
            mySelfId = "self",
            networkQuality = networkQuality,
            host = host,
        )
    }

    private fun dispatch(dispatcher: RoomEventDispatcher, event: RoomEvent) {
        val method = RoomEventDispatcher::class.java
            .getDeclaredMethod("dispatch", RoomEvent::class.java)
        method.isAccessible = true
        method.invoke(dispatcher, event)
    }

    private fun localParticipant(): LocalParticipant = mockk<LocalParticipant>(relaxed = true).also {
        every { it.identity } returns Participant.Identity("self.1")
    }

    private fun remoteParticipant(identity: String = "+123.1"): RemoteParticipant =
        mockk<RemoteParticipant>(relaxed = true).also {
            every { it.identity } returns Participant.Identity(identity)
        }

    private fun qualityEvent(participant: Participant, quality: ConnectionQuality) =
        RoomEvent.ConnectionQualityChanged(room, participant, quality)

    // ------------------------------------------------------------------ C10a
    // A remote POOR reading now reaches the verdict unit (that is the new feature), but it must NOT
    // touch the rating trigger: "was MY network bad" would otherwise become "was anyone's".
    @Test
    fun `remote quality feeds the verdict unit without touching the rating chain`() {
        val dispatcher = buildDispatcher()

        dispatch(dispatcher, qualityEvent(remoteParticipant(), ConnectionQuality.POOR))

        assertEquals(emptyList<Boolean>(), networkPoorCalls)
        verify(exactly = 1) { networkQuality.onQualityChanged("+123.1", ConnectionQuality.POOR, false) }
    }

    // ------------------------------------------------------------------ C10b/c/d
    @Test
    fun `local quality keeps the original poor classification including UNKNOWN`() {
        val dispatcher = buildDispatcher()
        val local = localParticipant()

        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.EXCELLENT))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.UNKNOWN))

        // UNKNOWN counts as poor here on purpose — the render-side mapping treats it as EXCELLENT so
        // no badge flashes before the first stats batch, but changing it here would change the rating
        // sheet's trigger rate.
        assertEquals(listOf(true, false, true), networkPoorCalls)
        verify(exactly = 3) { networkQuality.onQualityChanged("self.1", any(), true) }
    }

    // ------------------------------------------------------------------ C11
    // The verdict cleanup sits ABOVE the switch/reconnect guard, and that guard is untouched.
    @Test
    fun `participant disconnected clears the verdict even while the switch guard is active`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.SWITCHING_SERVER)
        val dispatcher = buildDispatcher()

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, remoteParticipant("+456.1")))

        verify(exactly = 1) { networkQuality.onParticipantLeft("+456.1") }
        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    // ------------------------------------------------------------------ C12
    // The 60 s debounced toast chain is gone: no NetworkConnectionPoorException producer remains, so
    // a weak network can never reach CallErrorHandler's `else` branch (which ends the call).
    @Test
    fun `repeated local poor readings never raise a call error`() {
        val dispatcher = buildDispatcher()
        val local = localParticipant()

        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))

        verify(exactly = 0) { roomCtl.collectError(any()) }
    }
}
