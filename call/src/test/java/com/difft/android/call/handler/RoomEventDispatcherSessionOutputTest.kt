package com.difft.android.call.handler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CountDownTimerData
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import livekit.LivekitTemptalk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [RoomEventDispatcher]'s writes into the session UI state ([com.difft.android.call.session.CallSessionUiState])
 * through the room-event collector body. Robolectric because the dispatcher reads `SystemClock` and
 * resolves barrage strings. Rows: T3-04, T3-05, T3-06, T3-07, T3-10, T3-12, T3-13.
 * Also the remote-end hand-off to the session (T4-18, T5-13): the call data is dropped first, then the
 * session's end seam is called, and no broadcast is sent. The posted hop behind that seam is pinned
 * in [RoomEventDispatcherRemoteEndHopTest].
 * And the connect / remote-join steps the session hangs its projection writes on (T5-03, T5-04, T5-05).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomEventDispatcherSessionOutputTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val scheduler get() = testDispatcher.scheduler
    private lateinit var scope: CoroutineScope
    private lateinit var f: RoomEventDispatcherFixture
    private val sharer = MutableStateFlow<RemoteParticipant?>(null)

    /** Ordered trace: `share=<value>` at each sharer write, `reveal sharer=<identity|null>` at each reveal. */
    private val trace = mutableListOf<String>()
    private val events = mutableListOf<CallSessionUiEvent>()

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(testDispatcher)
        f = RoomEventDispatcherFixture(scope)
        f.participantManager = mockk<ParticipantManager>(relaxed = true) {
            every { screenSharingUser } returns sharer
            every { setScreenSharingUser(any()) } answers {
                trace += "share=${f.uiState.isShareScreening.value}"
                sharer.value = firstArg()
            }
        }
        scope.launch {
            f.uiState.events.collect {
                if (it == CallSessionUiEvent.RevealCallChrome) trace += "reveal sharer=${sharer.value?.identity?.value}"
                events += it
            }
        }
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `T3-04 a remote share start sets the flag, reveals the chrome once and announces the sharer`() {
        val dispatcher = f.build()
        val alice = remote("alice.1", sharing = true)

        f.dispatch(dispatcher, trackSubscribed(alice))
        scheduler.runCurrent()

        assertEquals(true, f.uiState.isShareScreening.value)
        assertEquals(listOf<CallSessionUiEvent>(CallSessionUiEvent.RevealCallChrome), events)
        assertSame(alice, sharer.value)
        val barrage = f.barrages.single()
        assertSame(alice, barrage.first)
        assertEquals(ResUtils.getString(R.string.call_barrage_message_screensharing), barrage.second)
    }

    @Test
    fun `T3-05 a second subscribe from the same sharer neither reveals nor announces again`() {
        val dispatcher = f.build()
        val alice = remote("alice.1", sharing = true)

        f.dispatch(dispatcher, trackSubscribed(alice))
        f.dispatch(dispatcher, trackSubscribed(alice))
        scheduler.runCurrent()

        assertEquals(1, events.count { it == CallSessionUiEvent.RevealCallChrome })
        assertEquals(1, f.barrages.size)
    }

    @Test
    fun `T3-06 a share stop clears the flag, then the sharer, then reveals the chrome`() {
        val dispatcher = f.build()
        val alice = remote("alice.1", sharing = false)
        f.uiState.setShareScreening(true)
        sharer.value = alice

        f.dispatch(dispatcher, RoomEvent.TrackUnsubscribed(f.room, mockk(relaxed = true), mockk(relaxed = true), alice))
        scheduler.runCurrent()

        assertEquals(false, f.uiState.isShareScreening.value)
        assertEquals(listOf("share=false", "reveal sharer=null"), trace)
        assertEquals(listOf<CallSessionUiEvent>(CallSessionUiEvent.RevealCallChrome), events)
    }

    @Test
    fun `T3-07 a share stop while reconnecting keeps the flag and emits nothing`() {
        every { f.roomCtl.callStatus } returns MutableStateFlow(CallStatus.RECONNECTING)
        val dispatcher = f.build()
        val alice = remote("alice.1", sharing = false)
        f.uiState.setShareScreening(true)
        sharer.value = alice

        f.dispatch(dispatcher, RoomEvent.TrackUnsubscribed(f.room, mockk(relaxed = true), mockk(relaxed = true), alice))
        scheduler.runCurrent()

        assertEquals(true, f.uiState.isShareScreening.value)
        assertSame(alice, sharer.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `T3-10 reconnecting disables speaking, speakers re-enable it, 3000 ms of silence disables it again`() {
        val dispatcher = f.build()
        val values = mutableListOf(f.uiState.speakingEnabled.value)

        f.dispatch(dispatcher, RoomEvent.Reconnecting(f.room))
        values += f.uiState.speakingEnabled.value
        f.dispatch(dispatcher, RoomEvent.ActiveSpeakersChanged(f.room, listOf(remote("bob.1", sharing = false))))
        values += f.uiState.speakingEnabled.value
        scheduler.advanceTimeBy(2_999)
        scheduler.runCurrent()
        assertEquals("watchdog has not fired before 3000 ms", true, f.uiState.speakingEnabled.value)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        values += f.uiState.speakingEnabled.value

        assertEquals(listOf(true, false, true, false), values)
    }

    @Test
    fun `T3-12 a SET_COUNTDOWN of 3 s ticks the session countdown text down to 00_00`() {
        f.timerManager = TimerManager(scope)
        val data = CountDownTimerData(currentTimeMs = 1_000, durationMs = 3_000, expiredTimeMs = 4_000, operatorIdentity = "")
        every { f.rtm.handleDataReceived(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<(CountDownTimerData, String) -> Unit>(6).invoke(data, RTM_MESSAGE_TOPIC_SET_COUNTDOWN)
        }
        val dispatcher = f.build()
        val ticks = mutableListOf<String>()

        f.dispatch(dispatcher, mockk<RoomEvent.DataReceived>(relaxed = true))
        ticks += f.uiState.countDownDurationStr.value
        repeat(3) {
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            ticks += f.uiState.countDownDurationStr.value
        }

        assertEquals(listOf("00:03", "00:02", "00:01", "00:00"), ticks)
    }

    @Test
    fun `T3-13 resubscription settling after a reconnect bumps the reconnect generation once`() {
        val settled = slot<() -> Unit>()
        every { f.screenSharePreWarmer.markReconnected(capture(settled)) } answers { }
        val dispatcher = f.build()

        f.dispatch(dispatcher, RoomEvent.Reconnected(f.room))
        settled.captured.invoke()

        assertEquals(1, f.uiState.reconnectCount.value)
    }

    @Test
    fun `T4-18 an RTM END_CALL drops the call data, then asks the session to end, with no broadcast`() {
        val order = recordRemoteEndOrder()
        every { f.rtm.handleDataReceived(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<() -> Unit>(4).invoke() // onEndCall
        }
        val dispatcher = f.build(roomId = "r1")

        f.dispatch(dispatcher, mockk<RoomEvent.DataReceived>(relaxed = true))

        assertEquals(listOf("remove r1", "end r1"), order)
        assertTrue(shadowOf(ApplicationProvider.getApplicationContext<Application>()).broadcastIntents.isEmpty())
    }

    @Test
    fun `T5-13 a server end-call for another room is ignored, for this room it removes then ends`() {
        val order = recordRemoteEndOrder()
        val activeRoom = mockk<Room>(relaxed = true)
        every { f.roomCtl.roomOrNull() } answers { activeRoom }
        every { f.rtm.handleDataReceived(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<() -> Unit>(5).invoke() // onServerEndCall
        }
        val dispatcher = f.build(roomId = "r1")

        f.dispatch(dispatcher, mockk<RoomEvent.DataReceived>(relaxed = true))
        assertTrue("another room: nothing", order.isEmpty())

        every { f.roomCtl.roomOrNull() } answers { f.room }
        f.dispatch(dispatcher, mockk<RoomEvent.DataReceived>(relaxed = true))
        assertEquals(listOf("remove r1", "end r1"), order)
    }

    @Test
    fun `T5-03 a 1v1 connect with nobody else in the room arms the 1v1 timeout and stays ringing`() {
        stubConnectedRoom(remotes = emptyMap())
        val dispatcher = f.build(CallType.ONE_ON_ONE.type)

        f.dispatch(dispatcher, RoomEvent.Connected(f.room))

        verify(exactly = 1) { f.timeoutMonitor.start1V1Timeout(RoomEventDispatcherFixture.ROOM_ID) }
        assertTrue("never CONNECTED: the 1v1 timeout keeps the caller CALLING", f.connectStepCalls.isEmpty())
        verify(exactly = 1) { f.callDataManager.updateCallingState(RoomEventDispatcherFixture.ROOM_ID, true) }
    }

    @Test
    fun `T5-04 a group or instant connect is connected once, without a 1v1 timeout, and publishes muted silence`() {
        for (type in listOf(CallType.GROUP.type, CallType.INSTANT.type)) {
            f = RoomEventDispatcherFixture(scope)
            stubConnectedRoom(remotes = emptyMap())
            every { f.room.ttCallResp } returns mockk<LivekitTemptalk.TTCallResponse>(relaxed = true) {
                every { callOptions.autoPublishSilenceAudio } returns true
            }

            f.dispatch(f.build(type), RoomEvent.Connected(f.room))

            verify(exactly = 0) { f.timeoutMonitor.start1V1Timeout(any()) }
            assertEquals(type, listOf(RoomEventDispatcherFixture.CONNECTED), f.connectStepCalls)
            assertEquals(type, listOf(Triple(true, true, false)), f.setMicCalls)
        }
    }

    @Test
    fun `T5-05 a remote joining a 1v1 stops the ringing and the timeout before the call is connected`() {
        val dispatcher = f.build(CallType.ONE_ON_ONE.type)

        f.dispatch(dispatcher, RoomEvent.ParticipantConnected(f.room, remote("bob.1", sharing = false)))

        assertEquals(
            listOf(RoomEventDispatcherFixture.STOP_RING, RoomEventDispatcherFixture.CONNECTED),
            f.connectStepCalls,
        )
    }

    /** The `Room` surface `onConnected` reads: no local mic publication, value-class ids, [remotes]. */
    private fun stubConnectedRoom(remotes: Map<Participant.Identity, RemoteParticipant>) {
        val local = mockk<LocalParticipant>(relaxed = true) {
            every { sid } returns Participant.Sid("local-sid")
            every { identity } returns Participant.Identity("local-identity")
            every { getTrackPublication(any()) } returns null
        }
        every { f.room.localParticipant } returns local
        every { f.room.sid } returns Room.Sid("room-sid")
        every { f.room.remoteParticipants } returns remotes
    }

    /** `remove <rid>` at each call-data removal and `end <rid>` at each session end request, in order. */
    private fun recordRemoteEndOrder(): List<String> {
        val order = mutableListOf<String>()
        f.callDataManager = mockk(relaxed = true) {
            every { removeCallData(any()) } answers { order += "remove ${firstArg<String>()}" }
        }
        f.onRemoteEndRequested = { order += "end $it" }
        return order
    }

    private fun remote(identity: String, sharing: Boolean): RemoteParticipant = mockk(relaxed = true) {
        every { this@mockk.identity } returns Participant.Identity(identity)
        every { getTrackPublication(Track.Source.SCREEN_SHARE) } returns
            if (sharing) mockk<RemoteTrackPublication>(relaxed = true) else null
    }

    private fun trackSubscribed(participant: RemoteParticipant): RoomEvent.TrackSubscribed {
        val publication = mockk<RemoteTrackPublication>(relaxed = true) {
            every { source } returns Track.Source.SCREEN_SHARE
        }
        return RoomEvent.TrackSubscribed(f.room, mockk(relaxed = true), publication, participant)
    }
}
