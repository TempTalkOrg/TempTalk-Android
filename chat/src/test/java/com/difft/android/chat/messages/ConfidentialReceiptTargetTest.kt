package com.difft.android.chat.messages

import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConfidentialReceiptTargetTest {

    private val me = "+10000000001"
    private val peer = "+10000000002"
    private val stranger = "+10000000003"
    private val gid = "group-1"

    private fun message(
        fromWho: String = me,
        mode: Int = CONFIDENTIAL,
        roomId: String = peer,
        roomType: Int = ONE_TO_ONE,
    ) = MessageModel().apply {
        this.fromWho = fromWho
        this.mode = mode
        this.roomId = roomId
        this.roomType = roomType
        this.timeStamp = 1_700_000_000_000L
    }

    private fun pick(vararg candidates: MessageModel, senderId: String = peer, members: Set<String> = emptySet()) =
        pickConfidentialReceiptTarget(candidates.toList(), me, senderId) { group -> group == gid && senderId in members }

    @Test
    fun `confidential message in the 1v1 conversation with the viewer is the target`() {
        val target = message()
        assertSame(target, pick(target))
    }

    @Test
    fun `normal message is never wiped by a confidential receipt`() {
        assertNull(pick(message(mode = NORMAL)))
    }

    @Test
    fun `message sent by someone else is not a target`() {
        assertNull(pick(message(fromWho = peer)))
    }

    @Test
    fun `1v1 message to another contact is not reachable by the viewer`() {
        assertNull(pick(message(roomId = stranger)))
    }

    @Test
    fun `group message is a target only when the viewer is a member`() {
        val groupMessage = message(roomId = gid, roomType = GROUP)
        assertSame(groupMessage, pick(groupMessage, members = setOf(peer)))
        assertNull(pick(groupMessage, members = emptySet()))
    }

    @Test
    fun `the qualifying message is chosen among same-timestamp candidates`() {
        val otherConversation = message(roomId = stranger)
        val normal = message(mode = NORMAL)
        val target = message()
        assertSame(target, pick(otherConversation, normal, target))
    }

    private companion object {
        const val NORMAL = 0
        const val CONFIDENTIAL = 1
        const val ONE_TO_ONE = 0
        const val GROUP = 1
    }
}
