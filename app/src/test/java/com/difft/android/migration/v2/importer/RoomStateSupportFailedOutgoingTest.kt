package com.difft.android.migration.v2.importer

import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class RoomStateSupportFailedOutgoingTest {

    private val roomId = "+10000000002"

    @Test
    fun `failed text row tags the room`() {
        assertTrue(RoomStateSupport.hasFailedOutgoingMessage(roomId, listOf(failedRow())))
    }

    @Test
    fun `failed screenshot notice does not tag the room`() {
        val screenshot = failedRow().apply { screenShotJson = """{"realSource":{}}""" }

        assertFalse(RoomStateSupport.hasFailedOutgoingMessage(roomId, listOf(screenshot)))
    }

    @Test
    fun `empty screenShotJson still counts as a real outgoing row`() {
        val row = failedRow().apply { screenShotJson = "" }

        assertTrue(RoomStateSupport.hasFailedOutgoingMessage(roomId, listOf(row)))
    }

    private fun failedRow() = MessageModel().apply {
        roomId = this@RoomStateSupportFailedOutgoingTest.roomId
        type = MessageModel.TYPE_TEXT
        sendType = MessageModel.SEND_TYPE_FAILED
    }
}
