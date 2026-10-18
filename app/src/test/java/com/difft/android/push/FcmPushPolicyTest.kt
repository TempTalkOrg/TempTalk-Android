package com.difft.android.push

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decisions behind the #1347 FCM call-invite refresh and the server-push fallback skip. */
class FcmPushPolicyTest {

    private fun call(
        roomId: String,
        type: CallType,
        conversation: String?,
        callerUid: String?,
        notifying: Boolean = true,
    ) = CallData(
        type = type.type,
        version = 1,
        createdAt = 0L,
        roomId = roomId,
        caller = CallDataCaller(uid = callerUid, did = 1),
        conversation = conversation,
        encMeta = null,
        notifying = notifying,
    )

    @Test
    fun `R1-F4 instant call matches its caller uid although conversation is null`() {
        val instant = call("r-instant", CallType.INSTANT, conversation = null, callerUid = "alice")

        assertEquals(listOf("r-instant"), matchNotifyingCallRoomIds(listOf(instant), gid = null, uid = "alice"))
    }

    @Test
    fun `one on one call matches by conversation or caller uid`() {
        val byConversation = call("r1", CallType.ONE_ON_ONE, conversation = "alice", callerUid = "bob")
        val byCaller = call("r2", CallType.ONE_ON_ONE, conversation = "carol", callerUid = "alice")
        val other = call("r3", CallType.ONE_ON_ONE, conversation = "dave", callerUid = "erin")

        val matched = matchNotifyingCallRoomIds(listOf(byConversation, byCaller, other), gid = null, uid = "alice")

        assertEquals(listOf("r1", "r2"), matched)
    }

    @Test
    fun `gid takes precedence and only matches the group conversation`() {
        val group = call("rg", CallType.GROUP, conversation = "gid-1", callerUid = "alice")
        val otherGroup = call("rg2", CallType.GROUP, conversation = "gid-2", callerUid = "alice")
        val callerOnly = call("r-instant", CallType.INSTANT, conversation = null, callerUid = "alice")

        val matched = matchNotifyingCallRoomIds(listOf(group, otherGroup, callerOnly), gid = "gid-1", uid = "alice")

        assertEquals(listOf("rg"), matched)
    }

    @Test
    fun `calls that are not notifying never match`() {
        val ended = call("r1", CallType.ONE_ON_ONE, conversation = "alice", callerUid = "alice", notifying = false)

        assertTrue(matchNotifyingCallRoomIds(listOf(ended), gid = null, uid = "alice").isEmpty())
    }

    @Test
    fun `push without gid and uid matches nothing`() {
        val call = call("r1", CallType.ONE_ON_ONE, conversation = null, callerUid = null)

        assertTrue(matchNotifyingCallRoomIds(listOf(call), gid = null, uid = null).isEmpty())
        assertTrue(matchNotifyingCallRoomIds(listOf(call), gid = "", uid = "").isEmpty())
    }

    @Test
    fun `server uid push skips the fallback notification`() {
        assertTrue(isServerNotifyPush(uid = "server", notifyType = 0))
    }

    @Test
    fun `negative notifyType push skips the fallback notification`() {
        assertTrue(isServerNotifyPush(uid = "alice", notifyType = -1))
    }

    @Test
    fun `ordinary message push keeps the fallback notification`() {
        assertFalse(isServerNotifyPush(uid = "alice", notifyType = 0))
        assertFalse(isServerNotifyPush(uid = null, notifyType = 1))
    }
}
