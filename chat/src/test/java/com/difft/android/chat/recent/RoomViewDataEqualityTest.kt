package com.difft.android.chat.recent

import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [RoomViewData] is what actually gates a conversation-list refresh: it is emitted through a
 * `MutableStateFlow` (which drops equal values) and diffed by `DiffUtil.areContentsTheSame`, which
 * is implemented as `==`. If `sendStatus` were not part of the generated `equals`, flipping a room
 * to FAILED would produce an identical view model and the tag would never appear — no matter how
 * correct the data layer is.
 *
 * `RoomModel.equals` matters too, but only as a secondary signal; this is the primary gate.
 *
 * Covers T3-5.
 */
class RoomViewDataEqualityTest {

    private fun room(sendStatus: Int) = RoomViewData(
        roomId = "r1",
        roomName = "Room",
        lastDisplayContent = "hello",
        lastActiveTime = 1_000L,
        sendStatus = sendStatus,
    )

    @Test
    fun `rooms differing only in sendStatus are not equal`() {
        val clean = room(ROOM_SEND_STATUS_NONE)
        val failed = room(ROOM_SEND_STATUS_FAILED)

        assertNotEquals(clean, failed)
        assertNotEquals(clean.hashCode(), failed.hashCode())
    }

    @Test
    fun `rooms with the same sendStatus stay equal`() {
        assertEquals(room(ROOM_SEND_STATUS_FAILED), room(ROOM_SEND_STATUS_FAILED))
    }

    @Test
    fun `sendStatus defaults to NONE so synthesized rooms compile and read clean`() {
        // The default is what lets the instant-call placeholder room (no RoomModel behind it) be
        // constructed without the field. It must read as "no failure", not as an unset sentinel.
        val synthesized = RoomViewData(roomId = "r-call")

        assertEquals(ROOM_SEND_STATUS_NONE, synthesized.sendStatus)
    }

    @Test
    fun `copy preserves sendStatus`() {
        // The adapter path re-emits via copy() in places; a dropped field there would look exactly
        // like a data-layer bug.
        val failed = room(ROOM_SEND_STATUS_FAILED)

        assertEquals(ROOM_SEND_STATUS_FAILED, failed.copy(roomName = "renamed").sendStatus)
    }
}
