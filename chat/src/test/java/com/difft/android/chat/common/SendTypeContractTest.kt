package com.difft.android.chat.common

import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the two spellings of the `message.sendType` domain against each other.
 *
 * `SendType` lives in :chat; the room-level clear-side recompute runs in :database, which does NOT
 * depend on :chat and therefore mirrors the values as `MessageModel.SEND_TYPE_*`. This test lives
 * in :chat because it is the only module where BOTH declarations are visible — if either side ever
 * drifts, it fails here rather than producing a silently wrong SQL predicate.
 *
 * Covers T3-3.
 */
class SendTypeContractTest {

    @Test
    fun `database sendType constants match the chat SendType enum`() {
        assertEquals(SendType.Sending.rawValue, MessageModel.SEND_TYPE_SENDING)
        assertEquals(SendType.Sent.rawValue, MessageModel.SEND_TYPE_SENT)
        assertEquals(SendType.SentFailed.rawValue, MessageModel.SEND_TYPE_FAILED)
    }

    @Test
    fun `the enum has no value the database side is unaware of`() {
        val mirrored = setOf(
            MessageModel.SEND_TYPE_SENDING,
            MessageModel.SEND_TYPE_SENT,
            MessageModel.SEND_TYPE_FAILED,
        )
        assertEquals(mirrored, SendType.entries.map { it.rawValue }.toSet())
    }
}
