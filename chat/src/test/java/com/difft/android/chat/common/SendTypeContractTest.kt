package com.difft.android.chat.common

import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Test











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
