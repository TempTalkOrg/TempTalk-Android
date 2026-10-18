package com.difft.android.call.handler

import android.os.Looper
import com.difft.android.base.call.CallActionType
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_END_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SERVER_END_CALL
import com.difft.android.call.data.RtmDataPacket
import com.difft.android.call.data.RtmMessage
import com.difft.android.call.data.ServerEndCallRtmMessage
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallEndController
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionEndReason
import com.difft.android.call.session.CallSessionGraph
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The session graph's remote-end seam (the `RoomEventHost.onRemoteEndRequested` it binds) always
 * reaches the end controller as a post to the real main Looper on the session's `Job`: from the RTM
 * END_CALL path, whose `RtmMessageHandler` decodes on a real IO worker, and from the server
 * end-call path, which runs on main. Posted, never inline, so the end sequence never runs inside the
 * room-event or RTM handler; a session ended before the post runs drops it.
 * No `TestDispatcherRule`: `Dispatchers.Main` must be the Robolectric main Looper. Rows: T4-31, T4-32.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class RoomEventDispatcherRemoteEndHopTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val roomEventScope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var h: CallSessionTestHarness
    private lateinit var session: CallSession
    private lateinit var f: RoomEventDispatcherFixture

    /** One entry per end-controller `onRemoteControl`: whether it ran on the main Looper. */
    private val endCalls = Collections.synchronizedList(mutableListOf<Boolean>())

    @Before
    fun setUp() {
        mockkConstructor(CallEndController::class)
        every { anyConstructed<CallEndController>().onRemoteControl(any(), any()) } answers {
            endCalls += Looper.myLooper() == Looper.getMainLooper()
        }
        h = CallSessionTestHarness(main = Dispatchers.Main.immediate)
        session = h.start()
        f = RoomEventDispatcherFixture(roomEventScope)
        f.onRemoteEndRequested = { rid -> endSeam.invoke(session.graph, rid) }
        mainLooper.pause()
    }

    @After
    fun tearDown() {
        roomEventScope.cancel()
        h.endAll()
        mainLooper.idle()
        unmockkAll()
    }

    @Test
    fun `T4-31 an RTM END_CALL decoded on an IO worker reaches the end controller only via the main Looper`() {
        val removedOn = AtomicReference<Thread>()
        val handedOver = CountDownLatch(1)
        f.callDataManager = mockk(relaxed = true) {
            every { removeCallData(any()) } answers { removedOn.set(Thread.currentThread()) }
        }
        val seam = f.onRemoteEndRequested
        f.onRemoteEndRequested = { rid -> seam(rid); handedOver.countDown() }
        f.rtm = RtmMessageHandler(
            room = f.room,
            scope = session.mainScope,
            encryptor = { _, _ -> null },
            decryptor = { _, _ -> RtmMessage(topic = RTM_MESSAGE_TOPIC_END_CALL) },
        )
        val dispatcher = f.build(roomId = ROOM_ID)

        f.dispatch(dispatcher, dataReceived(RTM_MESSAGE_TOPIC_END_CALL, mockk<RemoteParticipant>(relaxed = true), ByteArray(0)))

        assertTrue("RTM worker handed over", handedOver.await(AWAIT_S, TimeUnit.SECONDS))
        assertNotEquals("call data removed on the IO worker", Looper.getMainLooper().thread, removedOn.get())
        assertEquals(listOf(ROOM_ID), f.remoteEndRequests)
        assertTrue("not before the main Looper runs", endCalls.isEmpty())

        mainLooper.idle()

        assertEquals(listOf(true), endCalls.toList())
        verify(exactly = 1) { anyConstructed<CallEndController>().onRemoteControl(CallActionType.HANGUP, ROOM_ID) }
    }

    @Test
    fun `T4-32 a server end-call handled on main reaches the end controller one Looper pass later, on main`() {
        every { f.roomCtl.roomOrNull() } answers { f.room }
        f.rtm = realRtm()
        val dispatcher = f.build(roomId = ROOM_ID)

        f.dispatch(dispatcher, serverEndCall())

        assertEquals(listOf(ROOM_ID), f.remoteEndRequests)
        assertTrue("not inline", endCalls.isEmpty())

        mainLooper.idle()

        assertEquals(listOf(true), endCalls.toList())
        verify(exactly = 1) { anyConstructed<CallEndController>().onRemoteControl(CallActionType.HANGUP, ROOM_ID) }
    }

    @Test
    fun `T4-32 a session ended before the posted hop runs never reaches the end controller`() {
        every { f.roomCtl.roomOrNull() } answers { f.room }
        f.rtm = realRtm()
        val dispatcher = f.build(roomId = ROOM_ID)

        f.dispatch(dispatcher, serverEndCall())
        session.end(CallSessionEndReason.EXIT_CLEAR)
        h.cancelScope(session) // the session's last cleanup step
        mainLooper.idle()

        assertEquals(listOf(ROOM_ID), f.remoteEndRequests)
        assertTrue(endCalls.isEmpty())
    }

    private fun realRtm() = RtmMessageHandler(f.room, session.mainScope, { _, _ -> null }, { _, _ -> null })

    /** A valid server-injected packet: no participant, inner topic and timestamp matching the outer ones. */
    private fun serverEndCall(): RoomEvent.DataReceived {
        val inner = Json.encodeToString(ServerEndCallRtmMessage(topic = RTM_MESSAGE_TOPIC_SERVER_END_CALL, sendTimestamp = TS))
        val packet = Json.encodeToString(RtmDataPacket(payload = inner, signature = "", sendTimestamp = TS, uuid = "u1"))
        return dataReceived(RTM_MESSAGE_TOPIC_SERVER_END_CALL, participant = null, data = packet.toByteArray())
    }

    private fun dataReceived(topic: String, participant: RemoteParticipant?, data: ByteArray) =
        mockk<RoomEvent.DataReceived>(relaxed = true) {
            every { this@mockk.topic } returns topic
            every { this@mockk.participant } returns participant
            every { this@mockk.data } returns data
        }

    private companion object {
        const val ROOM_ID = "r1"
        const val TS = 5L
        const val AWAIT_S = 5L

        /** The graph's private `onRemoteEndRequested`, which `RoomEventHost` is bound to in production. */
        val endSeam = CallSessionGraph::class.java.getDeclaredMethod("onRemoteEndRequested", String::class.java)
            .apply { isAccessible = true }
    }
}
