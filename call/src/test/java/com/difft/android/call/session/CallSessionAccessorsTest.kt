package com.difft.android.call.session

import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.CallIntent
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** Release-safe read accessors of [CallSession], plus the mute-other failure toast. Rows: T2-15, T2-16, T2-18, T3-22, T5-30. */
class CallSessionAccessorsTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    // A control-button tap racing teardown must not crash.
    @Test
    fun `T2-15 isControlButtonClickEnabled in 1v1 reads roomStateOrNull and is false after release`() {
        h.components.callType.value = CallType.ONE_ON_ONE.type
        h.components.roomGetterThrows = true
        val session = h.start()

        h.components.roomState = Room.State.CONNECTED
        assertTrue("connected 1v1 -> control button enabled", session.isControlButtonClickEnabled())
        h.components.roomState = null
        assertFalse("released 1v1 -> disabled, not a crash", session.isControlButtonClickEnabled())
    }

    @Test
    fun `T2-16 uid list and group room name come from the participants snapshot and never read the room`() {
        h.components.callType.value = CallType.GROUP.type
        h.components.roomGetterThrows = true
        val callIntent = CallIntentFixture.build(CallIntent.Action.START_CALL, CallType.GROUP.type, roomName = "Team")
        val session = h.start(h.params(type = CallType.GROUP.type, callIntent = callIntent))
        val local = mockk<LocalParticipant>(relaxed = true)
        session.participantManager.setParticipants(listOf(local, remote("u1.device2"), remote("u2")))

        assertEquals(listOf("u1", "u2"), session.getCurrentCallUidList())
        assertEquals("Team (3)", session.getCallRoomName())
        verify(exactly = 0) { h.components.roomCtl.room }
    }

    @Test
    fun `T2-18 roomId and e2eeKey are readable and the clearE2eeKey cleanup step zero-fills the key`() = runTest(testDispatcher) {
        val session = h.start()
        val key = byteArrayOf(1, 2, 3, 4)

        session.graph.onRoomIdAssigned("r1")
        session.graph.onE2eeKeyAssigned(key)

        assertEquals("r1", session.getRoomId())
        assertSame(key, session.getE2eeKey())

        session.end(CallSessionEndReason.EXIT_CLEAR)
        h.cleanupStarts.single().second.single { it.name == "clearE2eeKey" }.action()

        assertTrue("key bytes zero-filled", key.all { it == 0.toByte() })
        assertNull(session.getE2eeKey())
        assertEquals("r1", session.getRoomId())
    }

    @Test
    fun `T3-22 a failed mute-other emits a Toast naming the participant`() {
        mockkObject(ResUtils)
        every { ResUtils.getString(R.string.call_mute_participant_failed, *anyVararg()) } answers {
            "mute failed ${(args[1] as Array<*>).single()}"
        }
        val session = h.start()
        session.graph.rtm = mockk<RtmMessageHandler>(relaxed = true) {
            every { toggleMute(any(), any()) } answers { secondArg<(Boolean) -> Unit>().invoke(false) }
        }
        val events = mutableListOf<CallSessionUiEvent>()
        val collector = CoroutineScope(testDispatcher)
        collector.launch { session.uiState.events.collect { events += it } }
        testDispatcher.scheduler.runCurrent()

        try {
            session.toggleMute(remote("bob.1"), "Bob")
            testDispatcher.scheduler.runCurrent()

            assertEquals(listOf<CallSessionUiEvent>(CallSessionUiEvent.Toast("mute failed Bob")), events)
        } finally {
            collector.cancel()
        }
    }

    private fun remote(identity: String): RemoteParticipant = mockk(relaxed = true) {
        every { this@mockk.identity } returns Participant.Identity(identity)
    }

    @Test
    fun `T5-30 getOneOnOnePeerId returns the joined remote identity, else the conversation id`() {
        val callIntent = CallIntentFixture.build(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type, conversationId = "u456")
        val session = h.start(h.params(callIntent = callIntent))

        assertEquals("u456", session.getOneOnOnePeerId())

        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("u123")
        session.participantManager.setParticipants(listOf(remote))

        assertEquals("u123", session.getOneOnOnePeerId())
    }
}
