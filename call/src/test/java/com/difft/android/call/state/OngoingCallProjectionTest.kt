package com.difft.android.call.state

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.appScope
import com.difft.android.call.CallIntent
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.session.CallSessionEndReason
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * OnGoingCallStateManager's session fields as a fenced projection: only the owning session writes
 * them, `endPresence` resets them (the incoming page's `controlMessage` excepted) and releases the
 * fence, a different session binding over a live owner resets them too, and a real session
 * publishes its identity, call type and presence from its start. The page flags follow the
 * process-wide count of call pages: the last one to go resets them.
 * Rows: T4-19..T4-24, T4-21b/T4-21c, T4-33, T5-15 (fence half).
 */
class OngoingCallProjectionTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val ogsm get() = h.ogsm

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T5-15 nothing is written before any session binds`() {
        assertFalse(OnGoingCallStateManager().publish(1L) { setCurrentRoomId("x") })
    }

    @Test
    fun `T4-19 a write for a session that does not own the projection is dropped`() {
        ogsm.bindSession(1L)

        assertFalse(ogsm.publish(2L) { setCurrentRoomId("x") })
        assertNull(ogsm.getCurrentRoomId())
    }

    @Test
    fun `T4-20 endPresence resets every session field, needAppLock and the header flag, keeps controlMessage and the page flags, then releases the fence`() {
        val control = OnGoingCallStateManager.ControlMessage(CallActionType.HANGUP, "r9")
        ogsm.bindSession(1L)
        ogsm.publish(1L) {
            setIsInCalling(true)
            setIsInCallEnding(true)
            setCurrentRoomId("r1")
            setClientCallId("c1")
            markInitiatorPreConnectCancelled()
            setConversationId("g1")
            setCallType(CallType.GROUP.type)
            updateCallingTime("r1", "00:05")
            markCallConnected()
            updateCallStatus(CallStatus.CONNECTED)
        }
        ogsm.setNeedAppLock(false)
        ogsm.setIsInForeground(true)
        ogsm.setIsInPipMode(true)
        ogsm.setIsInScreenSharing(true)
        ogsm.setChatHeaderCallVisibility(true)
        ogsm.updateControlMessage(control)

        assertTrue(ogsm.endPresence(1L))

        assertTrue(ogsm.needAppLock)
        assertFalse(ogsm.isInCalling())
        assertFalse(ogsm.isInCallEnding())
        assertNull(ogsm.getCurrentRoomId())
        assertNull(ogsm.getClientCallId())
        assertFalse(ogsm.isInitiatorPreConnectCancelled())
        assertNull(ogsm.getConversationId())
        assertTrue("page flags are the pages'", ogsm.isInForeground())
        assertTrue(ogsm.isInPipMode())
        assertTrue(ogsm.isInScreenSharing())
        assertEquals("", ogsm.callType())
        assertNull(ogsm.callingTime.value)
        assertEquals(0L, ogsm.callConnectedAt())
        assertEquals(CallStatus.UNKNOWN, ogsm.callStatus.value)
        assertFalse(ogsm.chatHeaderCallVisibility.value)
        assertEquals(control, ogsm.controlMessage.value)
        assertFalse("released", ogsm.publish(1L) { setIsInCalling(true) })
        assertFalse(ogsm.isInCalling())
    }

    @Test
    fun `T4-21 an old session's endPresence after a newer bind changes nothing`() {
        ogsm.bindSession(1L)
        ogsm.publish(1L) { setIsInCalling(true) }
        ogsm.bindSession(2L)
        ogsm.publish(2L) {
            setIsInCalling(true)
            setCurrentRoomId("r2")
        }

        assertFalse(ogsm.endPresence(1L))

        assertTrue(ogsm.isInCalling())
        assertEquals("r2", ogsm.getCurrentRoomId())
    }

    @Test
    fun `T4-21b a different session binding over a live owner resets its fields and fences it out`() {
        val control = OnGoingCallStateManager.ControlMessage(CallActionType.HANGUP, "r9")
        var connectedAtA = 0L
        ogsm.bindSession(1L)
        ogsm.publish(1L) {
            setIsInCalling(true)
            setIsInCallEnding(true)
            setCurrentRoomId("rA")
            setClientCallId("cA")
            markInitiatorPreConnectCancelled()
            setConversationId("gA")
            setCallType(CallType.GROUP.type)
            updateCallingTime("rA", "00:05")
            updateCallStatus(CallStatus.CONNECTED)
            connectedAtA = markCallConnected()
        }
        ogsm.setIsInForeground(true)
        ogsm.setIsInPipMode(true)
        ogsm.updateControlMessage(control)
        assertTrue(connectedAtA > 0L)

        // Session B binds before A's page ran endPresence.
        ogsm.bindSession(2L)

        assertFalse(ogsm.isInCalling())
        assertFalse(ogsm.isInCallEnding())
        assertNull(ogsm.getCurrentRoomId())
        assertNull(ogsm.getClientCallId())
        assertFalse(ogsm.isInitiatorPreConnectCancelled())
        assertNull(ogsm.getConversationId())
        assertEquals("", ogsm.callType())
        assertNull(ogsm.callingTime.value)
        assertEquals(0L, ogsm.callConnectedAt())
        assertEquals(CallStatus.UNKNOWN, ogsm.callStatus.value)
        assertTrue("window flags are not session fields", ogsm.isInForeground())
        assertTrue(ogsm.isInPipMode())
        assertEquals(control, ogsm.controlMessage.value)

        // A's late writes and its late endPresence are rejected.
        assertFalse(ogsm.publish(1L) { setCurrentRoomId("rA") })
        assertFalse(ogsm.endPresence(1L))
        assertNull(ogsm.getCurrentRoomId())
        assertTrue(ogsm.isInForeground())

        var connectedAtB = 0L
        assertTrue(ogsm.publish(2L) { connectedAtB = markCallConnected() })
        assertTrue("B stamps its own connect instant", connectedAtB >= connectedAtA)
        assertEquals(connectedAtB, ogsm.callConnectedAt())
    }

    @Test
    fun `T4-21c re-binding the current owner keeps its fields`() {
        var connectedAt = 0L
        ogsm.bindSession(1L)
        ogsm.publish(1L) {
            setIsInCalling(true)
            setCurrentRoomId("r1")
            updateCallStatus(CallStatus.CONNECTED)
            connectedAt = markCallConnected()
        }

        ogsm.bindSession(1L)

        assertTrue(ogsm.isInCalling())
        assertEquals("r1", ogsm.getCurrentRoomId())
        assertEquals(CallStatus.CONNECTED, ogsm.callStatus.value)
        assertEquals(connectedAt, ogsm.callConnectedAt())
        assertTrue(ogsm.publish(1L) { assertEquals("no re-stamp", connectedAt, markCallConnected()) })
    }

    @Test
    fun `T4-22 the call is presented from the session's start, before any page, until its end`() {
        val session = h.start()

        assertTrue(ogsm.isInCalling())
        assertNull("no room id yet", ogsm.getCurrentRoomId())

        session.end(CallSessionEndReason.EXIT_CLEAR)

        assertFalse(ogsm.isInCalling())
    }

    @Test
    fun `the last call page going away resets the page flags and keeps the header flag`() {
        h.start()
        ogsm.callPageAttached()
        ogsm.setIsInForeground(true)
        ogsm.setIsInPipMode(true)
        ogsm.setIsInScreenSharing(true)
        ogsm.setChatHeaderCallVisibility(true)

        ogsm.callPageDetached()

        assertFalse(ogsm.isInForeground())
        assertFalse(ogsm.isInPipMode())
        assertFalse(ogsm.isInScreenSharing())
        assertTrue("reset only at session end", ogsm.chatHeaderCallVisibility.value)
        assertTrue("the call goes on", ogsm.isInCalling())
    }

    @Test
    fun `an overlapping older page going away resets nothing while a newer page is up`() {
        ogsm.callPageAttached() // the old page, still being destroyed
        ogsm.callPageAttached() // the new page
        ogsm.setIsInForeground(true)
        ogsm.setIsInPipMode(true)

        ogsm.callPageDetached()

        assertTrue(ogsm.isInForeground())
        assertTrue(ogsm.isInPipMode())

        ogsm.callPageDetached()
        ogsm.callPageDetached() // an extra detach never drives the count below zero
        ogsm.callPageAttached()
        ogsm.setIsInPipMode(true)
        ogsm.callPageAttached()
        ogsm.callPageDetached()
        assertTrue("one page is still up", ogsm.isInPipMode())
    }

    @Test
    fun `T4-23 a start session publishes its identity and clears a stale cancel latch, a join publishes no clientCallId`() {
        OgsmTestState.write(ogsm, OgsmTestState.DETACHED_SESSION_ID) { markInitiatorPreConnectCancelled() }

        val start = h.start(startParams())

        assertEquals("c1", ogsm.getClientCallId())
        assertEquals("g1", ogsm.getConversationId())
        assertFalse(ogsm.isInitiatorPreConnectCancelled())

        // One call at a time: the start call ends before the join starts.
        start.end(CallSessionEndReason.EXIT_CLEAR)
        h.start(
            h.params(
                callIntent = CallIntentFixture.build(CallIntent.Action.JOIN_CALL, conversationId = "g2", clientCallId = "c2"),
            ),
        )

        // The start session's identity went with it; the join writes only its conversation.
        assertNull(ogsm.getClientCallId())
        assertEquals("g2", ogsm.getConversationId())
    }

    @Test
    fun `T4-24 the call type follows the session's room controller without any composition`() {
        h.components.callType.value = CallType.ONE_ON_ONE.type
        h.start()
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallType.ONE_ON_ONE.type, ogsm.callType())

        h.components.callType.value = CallType.INSTANT.type
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallType.INSTANT.type, ogsm.callType())
    }

    @Test
    fun `T4-33 page re-entry keeps a pending pre-connect cancel and only endPresence clears it`() {
        every { appScope } returns TestScope(StandardTestDispatcher()) // E1's signaling never starts
        val session = h.start(startParams())
        session.attachUi()
        assertEquals("c1", ogsm.getClientCallId())

        // E1: the initiator leaves while still CALLING; the latch goes through the session's publisher.
        session.requestExit(exitParams(), CallEndType.LEAVE)
        assertTrue(ogsm.isInitiatorPreConnectCancelled())

        // What a page re-entry can still do (onNewIntent does not re-publish the identity).
        session.attachUi()
        session.detachUi()

        assertTrue(ogsm.isInitiatorPreConnectCancelled())
        assertEquals("c1", ogsm.getClientCallId())

        session.graph.publisher.endPresence()

        assertFalse(ogsm.isInitiatorPreConnectCancelled())
    }

    private fun startParams() = h.params(
        callIntent = CallIntentFixture.build(CallIntent.Action.START_CALL, conversationId = "g1", clientCallId = "c1"),
    )

    private fun exitParams() = CallExitParams(
        roomId = null,
        callerId = "caller-1",
        callRole = CallRole.CALLER,
        callType = CallType.ONE_ON_ONE.type,
        conversationId = "g1",
    )
}
