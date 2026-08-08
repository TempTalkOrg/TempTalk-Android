package org.difft.app.database.models

import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that `sendStatus` participates in [RoomModel]'s value semantics.
 *
 * The conversation-list refresh gate is `RoomViewData` equality, not this one — but every new
 * `RoomModel` column is expected to be in `equals`/`hashCode`/`toString`, and omitting it here
 * would silently defeat any future `distinctUntilChanged`/cache consumer keyed on `RoomModel`.
 *
 * Covers T3-4.
 */
class RoomModelSendStatusEqualsTest {

    private fun room(status: Int) = RoomModel().apply {
        roomId = "r1"
        roomType = 0
        sendStatus = status
    }

    @Test
    fun `rooms differing only in sendStatus are not equal`() {
        val none = room(ROOM_SEND_STATUS_NONE)
        val failed = room(ROOM_SEND_STATUS_FAILED)

        assertNotEquals(none, failed)
        assertNotEquals(none.hashCode(), failed.hashCode())
    }

    @Test
    fun `rooms with the same sendStatus stay equal`() {
        assertEquals(room(ROOM_SEND_STATUS_FAILED), room(ROOM_SEND_STATUS_FAILED))
        assertEquals(room(ROOM_SEND_STATUS_FAILED).hashCode(), room(ROOM_SEND_STATUS_FAILED).hashCode())
    }

    @Test
    fun `sendStatus defaults to NONE`() {
        assertEquals(ROOM_SEND_STATUS_NONE, RoomModel().sendStatus)
    }

    @Test
    fun `toString exposes sendStatus`() {
        val rendered = room(ROOM_SEND_STATUS_FAILED).toString()
        assertTrue(rendered, rendered.contains("sendStatus=$ROOM_SEND_STATUS_FAILED"))
        assertFalse(room(ROOM_SEND_STATUS_NONE).toString().contains("sendStatus=$ROOM_SEND_STATUS_FAILED"))
    }
}
