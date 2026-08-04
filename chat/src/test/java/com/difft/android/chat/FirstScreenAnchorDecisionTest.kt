package com.difft.android.chat

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_SENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [decideFirstScreenAnchor] — pure, no Robolectric / WCDB / MockK.
 *
 * The SQL behind the two inputs (earliest failed message, first unread from others) is covered by
 * the `:database` query tests; this suite only pins the decision table.
 */
class FirstScreenAnchorDecisionTest {

    private fun decide(
        firstFailedTs: Long?,
        firstUnreadOthersTs: Long?,
    ): FirstScreenAnchor = decideFirstScreenAnchor(
        firstFailedTs = firstFailedTs,
        firstUnreadOthersTs = firstUnreadOthersTs,
    )

    // --- T6-1: no failed message -> today's behavior ---

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

    // --- T6-2: the first unread is the earlier item -> it wins ---

    @Test
    fun `first unread at or before the failure keeps read position anchor`() {
        // FromReadPosition opens ON the first unread, so this branch IS "anchor at the earliest".
        for (firstUnreadOthersTs in listOf(100L, 50L)) {
            assertEquals(
                "firstUnreadOthersTs=$firstUnreadOthersTs",
                FirstScreenAnchor.FromReadPosition,
                decide(firstFailedTs = 100L, firstUnreadOthersTs = firstUnreadOthersTs)
            )
        }
    }

    // --- T6-3: no distance test — an arbitrarily old failure still wins ---

    @Test
    fun `failure earlier than the first unread anchors regardless of distance`() {
        // The former proximity guard refused to anchor past one page. It is gone on purpose: the
        // divider is now session-scoped, so it no longer has to share the first screen.
        for (firstFailedTs in listOf(199L, 100L, 1L)) {
            assertEquals(
                "firstFailedTs=$firstFailedTs",
                FirstScreenAnchor.AtFailedMessage,
                decide(firstFailedTs = firstFailedTs, firstUnreadOthersTs = 200L)
            )
        }
    }

    // --- T6-4: the failure must not be mistaken for the first unread ---

    @Test
    fun `failure newer than read position does not look like the first unread`() {
        // firstUnreadOthersTs comes from the dedicated `fromWho != myId` query, so the failed
        // message (mine, and newer than readPosition) is NOT its value. Feeding the failure's own
        // timestamp here instead would make every room fall back to FromReadPosition.
        assertEquals(
            FirstScreenAnchor.AtFailedMessage,
            decide(firstFailedTs = 100L, firstUnreadOthersTs = 150L)
        )
    }

    // --- T6-5: no unread from others -> the failure is the only candidate ---

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

    // --- T6-11: DiffUtil rebinds when only the divider flag differs ---

    @Test
    fun `divider flag participates in ChatMessage equality`() {
        // ChatMessageAdapter's areContentsTheSame delegates to equals, so a divider that is
        // suppressed only reaches the UI if equals sees the flag.
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

    // --- T6-12: the gate reads `== FAILED`, so enabling SENDING cannot move the first screen ---

    @Test
    fun `first screen gate distinguishes FAILED from the other aggregates`() {
        assertEquals(0, ROOM_SEND_STATUS_NONE)
        assertEquals(2, ROOM_SEND_STATUS_FAILED)
        // A `== ROOM_SEND_STATUS_FAILED` gate (as opposed to `!= ROOM_SEND_STATUS_NONE`) is what
        // keeps a future SENDING aggregate from moving the first screen.
        assertNotEquals(ROOM_SEND_STATUS_FAILED, ROOM_SEND_STATUS_SENDING)
        assertNotEquals(ROOM_SEND_STATUS_NONE, ROOM_SEND_STATUS_SENDING)
    }
}
