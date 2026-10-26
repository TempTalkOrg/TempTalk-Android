package com.difft.android.call.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [CriticalAlertCallGate] (#1381): a critical alert is for the current call while a join of its
 * call is in flight, and while the active call is its call. Matching is room-first: when both
 * sides know the room only the room counts, otherwise the conversation does. Each join owns its
 * marker, and concurrent joins neither lose nor resurrect one.
 */
class CriticalAlertCallGateTest {

    private val callState = OnGoingCallStateManager()
    private val registry = CriticalAlertRoomRegistry()
    private val gate = CriticalAlertCallGate(callState, registry)

    @Test
    fun `an in-flight join matches an alert with its room id`() {
        gate.beginJoin(ROOM, CONVERSATION)

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertTrue("the room decides when both are known", gate.isForCurrentCall(OTHER_CONVERSATION, ROOM))
    }

    @Test
    fun `an in-flight join does not match the same conversation in another known room`() {
        gate.beginJoin(ROOM, CONVERSATION)

        assertFalse(gate.isForCurrentCall(CONVERSATION, OTHER_ROOM))
    }

    @Test
    fun `an in-flight join matches an alert without a room id on its conversation`() {
        gate.beginJoin(ROOM, CONVERSATION)

        assertTrue(gate.isForCurrentCall(CONVERSATION, null))
        assertTrue(gate.isForCurrentCall(CONVERSATION, ""))
        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, null))
    }

    @Test
    fun `an in-flight join without a room id matches on the conversation`() {
        gate.beginJoin(null, CONVERSATION)

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, ROOM))
    }

    @Test
    fun `an ended join no longer matches`() {
        val token = gate.beginJoin(ROOM, CONVERSATION)

        gate.endJoin(token)

        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall(CONVERSATION, null))
    }

    @Test
    fun `ending one join keeps another join's marker`() {
        val a = gate.beginJoin(OTHER_ROOM, OTHER_CONVERSATION)
        val b = gate.beginJoin(ROOM, CONVERSATION)

        gate.endJoin(a)

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, OTHER_ROOM))
        gate.endJoin(b)
        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
    }

    @Test
    fun `the active call matches an alert with its room id`() {
        activeCall(roomId = ROOM, conversationId = CONVERSATION)

        assertTrue(gate.isForCurrentCall(OTHER_CONVERSATION, ROOM))
    }

    @Test
    fun `the active call does not match the same conversation in another known room`() {
        activeCall(roomId = ROOM, conversationId = CONVERSATION)

        assertFalse(gate.isForCurrentCall(CONVERSATION, OTHER_ROOM))
        assertTrue("no alert room -> conversation", gate.isForCurrentCall(CONVERSATION, null))
    }

    @Test
    fun `a joined call matches by its bound room before its room id is published`() {
        activeCall(roomId = null, conversationId = CONVERSATION)
        gate.bindSessionRoom(SESSION, ROOM)

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse("another call in the conversation", gate.isForCurrentCall(CONVERSATION, OTHER_ROOM))
        assertTrue("no alert room -> conversation", gate.isForCurrentCall(CONVERSATION, null))
    }

    @Test
    fun `a started call without a room yet matches only alerts without a room`() {
        activeCall(roomId = null, conversationId = CONVERSATION)

        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertTrue(gate.isForCurrentCall(CONVERSATION, null))
    }

    @Test
    fun `a room bound to an earlier session is ignored`() {
        gate.bindSessionRoom(SESSION - 1, ROOM)
        activeCall(roomId = null, conversationId = CONVERSATION)

        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
    }

    @Test
    fun `the session's bound room wins over a room written by another session`() {
        activeCall(roomId = OTHER_ROOM, conversationId = CONVERSATION)
        gate.bindSessionRoom(SESSION, ROOM)

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall(CONVERSATION, OTHER_ROOM))
    }

    @Test
    fun `a bound room is unused once the session ended`() {
        activeCall(roomId = null, conversationId = CONVERSATION)
        gate.bindSessionRoom(SESSION, ROOM)
        callState.endPresence(SESSION)

        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
    }

    @Test
    fun `an alert for another room and conversation is not for the active call`() {
        activeCall(roomId = ROOM, conversationId = CONVERSATION)

        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, OTHER_ROOM))
        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, null))
    }

    @Test
    fun `a call that is ending does not match`() {
        activeCall(roomId = ROOM, conversationId = CONVERSATION)
        callState.publish(SESSION) { setIsInCallEnding(true) }

        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
    }

    @Test
    fun `no join and no call match nothing`() {
        assertFalse(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall(CONVERSATION, null))
    }

    @Test
    fun `an ended room silences its alerts without being the current call`() {
        registry.end(ROOM)

        assertTrue(gate.shouldSilence(CONVERSATION, ROOM))
        assertTrue("room decides, whatever the conversation", gate.shouldSilence(OTHER_CONVERSATION, ROOM))
        assertFalse("the current-call predicate is not affected", gate.isForCurrentCall(CONVERSATION, ROOM))
        assertTrue(gate.isRoomEnded(ROOM))
    }

    @Test
    fun `an ended room does not silence another room or a roomless alert`() {
        registry.end(ROOM)

        assertFalse(gate.shouldSilence(CONVERSATION, OTHER_ROOM))
        assertFalse(gate.shouldSilence(CONVERSATION, null))
        assertFalse(gate.shouldSilence(CONVERSATION, ""))
        assertFalse(gate.isRoomEnded(null))
        assertFalse(gate.isRoomEnded(""))
    }

    @Test
    fun `shouldSilence is still true for the current call and false for nothing`() {
        assertFalse(gate.shouldSilence(CONVERSATION, ROOM))

        gate.beginJoin(ROOM, CONVERSATION)

        assertTrue(gate.shouldSilence(CONVERSATION, ROOM))
    }

    @Test
    fun `concurrent joins neither lose a live marker nor keep an ended one`() {
        gate.beginJoin(ROOM, CONVERSATION)
        val start = CountDownLatch(1)

        val workers = (0 until WORKERS).map { worker ->
            thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(ROUNDS) { round ->
                    val token = gate.beginJoin("room-$worker-$round", "peer-$worker-$round")
                    gate.isForCurrentCall(CONVERSATION, ROOM)
                    gate.endJoin(token)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertTrue(gate.isForCurrentCall(CONVERSATION, ROOM))
        assertFalse(gate.isForCurrentCall("peer-0-0", "room-0-0"))
        assertFalse(gate.isForCurrentCall(OTHER_CONVERSATION, OTHER_ROOM))
    }

    private fun activeCall(roomId: String?, conversationId: String) {
        callState.bindSession(SESSION)
        callState.publish(SESSION) {
            setIsInCalling(true)
            setCurrentRoomId(roomId)
            setConversationId(conversationId)
        }
    }

    private companion object {
        const val SESSION = 3L
        const val WORKERS = 8
        const val ROUNDS = 500
        const val ROOM = "room-1"
        const val OTHER_ROOM = "room-2"
        const val CONVERSATION = "peer-1"
        const val OTHER_CONVERSATION = "peer-2"
    }
}
