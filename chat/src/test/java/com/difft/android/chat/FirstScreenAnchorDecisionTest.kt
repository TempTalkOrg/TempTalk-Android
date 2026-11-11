package com.difft.android.chat

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_SENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test







class FirstScreenAnchorDecisionTest {

    private fun decide(
        firstFailedTs: Long?,
        firstUnreadOthersTs: Long?,
    ): FirstScreenAnchor = decideFirstScreenAnchor(
        firstFailedTs = firstFailedTs,
        firstUnreadOthersTs = firstUnreadOthersTs,
    )

    @Test
    fun `no failed message keeps read position anchor`() {
        for (firstUnreadOthersTs in listOf(null, 500L)) {
            assertEquals(
                "firstUnreadOthersTs=$firstUnreadOthersTs",
                FirstScreenAnchor.FromReadPosition,
                decide(firstFailedTs = null, firstUnreadOthersTs = firstUnreadOthersTs)
            )
        }
    }

    

    @Test
    fun `first unread at or before the failure keeps read position anchor`() {
        
        for (firstUnreadOthersTs in listOf(100L, 50L)) {
            assertEquals(
                "firstUnreadOthersTs=$firstUnreadOthersTs",
                FirstScreenAnchor.FromReadPosition,
                decide(firstFailedTs = 100L, firstUnreadOthersTs = firstUnreadOthersTs)
            )
        }
    }

    

    @Test
    fun `failure earlier than the first unread anchors regardless of distance`() {
        
        for (firstFailedTs in listOf(199L, 100L, 1L)) {
            assertEquals(
                "firstFailedTs=$firstFailedTs",
                FirstScreenAnchor.AtFailedMessage,
                decide(firstFailedTs = firstFailedTs, firstUnreadOthersTs = 200L)
            )
        }
    }

    

    @Test
    fun `failure newer than read position does not look like the first unread`() {
        
        
        
        assertEquals(
            FirstScreenAnchor.AtFailedMessage,
            decide(firstFailedTs = 100L, firstUnreadOthersTs = 150L)
        )
    }

    

    @Test
    fun `no unread from others always anchors at the failure`() {
        for (firstFailedTs in listOf(1L, 100L, Long.MAX_VALUE)) {
            assertEquals(
                "firstFailedTs=$firstFailedTs",
                FirstScreenAnchor.AtFailedMessage,
                decide(firstFailedTs = firstFailedTs, firstUnreadOthersTs = null)
            )
        }
    }

    

    @Test
    fun `divider flag participates in ChatMessage equality`() {
        
        
        val withDivider = TextChatMessage().apply {
            id = "m1"
            authorId = "+10001"
            showNewMsgDivider = true
        }
        val withoutDivider = TextChatMessage().apply {
            id = "m1"
            authorId = "+10001"
            showNewMsgDivider = false
        }

        assertNotEquals(withDivider, withoutDivider)
    }

    

    @Test
    fun `first screen gate distinguishes FAILED from the other aggregates`() {
        assertEquals(0, ROOM_SEND_STATUS_NONE)
        assertEquals(2, ROOM_SEND_STATUS_FAILED)
        
        
        assertNotEquals(ROOM_SEND_STATUS_FAILED, ROOM_SEND_STATUS_SENDING)
        assertNotEquals(ROOM_SEND_STATUS_NONE, ROOM_SEND_STATUS_SENDING)
    }
}
