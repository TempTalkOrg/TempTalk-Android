package difft.android.messageserialization.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test










class RoomSendStatusTest {

    
    
    
    @Test
    fun `status constants have the documented values`() {
        assertEquals(0, ROOM_SEND_STATUS_NONE)
        assertEquals(1, ROOM_SEND_STATUS_SENDING)
        assertEquals(2, ROOM_SEND_STATUS_FAILED)
    }

    
    @Test
    fun `aggregateRoomSendStatus prioritises failure over sending`() {
        assertEquals(ROOM_SEND_STATUS_NONE, aggregateRoomSendStatus(hasFailed = false, hasSending = false))
        assertEquals(ROOM_SEND_STATUS_FAILED, aggregateRoomSendStatus(hasFailed = true, hasSending = false))
        assertEquals(ROOM_SEND_STATUS_SENDING, aggregateRoomSendStatus(hasFailed = false, hasSending = true))
        assertEquals(ROOM_SEND_STATUS_FAILED, aggregateRoomSendStatus(hasFailed = true, hasSending = true))
    }

    @Test
    fun `aggregateRoomSendStatus defaults hasSending to false`() {
        assertEquals(ROOM_SEND_STATUS_NONE, aggregateRoomSendStatus(hasFailed = false))
        assertEquals(ROOM_SEND_STATUS_FAILED, aggregateRoomSendStatus(hasFailed = true))
    }

    
    
    @Test
    fun `needsSendStatusRecompute only admits already-flagged rooms`() {
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        assertTrue(needsSendStatusRecompute(ROOM_SEND_STATUS_SENDING))
        assertTrue(needsSendStatusRecompute(ROOM_SEND_STATUS_FAILED))
    }

    
    @Test
    fun `resolveRoomSendStatus writes nothing when the value would not change`() {
        assertNull(resolveRoomSendStatus(ROOM_SEND_STATUS_NONE, hasFailed = false))
        assertNull(resolveRoomSendStatus(ROOM_SEND_STATUS_FAILED, hasFailed = true))
    }

    @Test
    fun `resolveRoomSendStatus clears a flagged room whose failure is gone`() {
        assertEquals(ROOM_SEND_STATUS_NONE, resolveRoomSendStatus(ROOM_SEND_STATUS_FAILED, hasFailed = false))
        assertEquals(ROOM_SEND_STATUS_NONE, resolveRoomSendStatus(ROOM_SEND_STATUS_SENDING, hasFailed = false))
    }

    
    
    
    @Test
    fun `resolveRoomSendStatus never escalates a NONE room to FAILED`() {
        
        
        
        listOf(true, false).forEach { hasFailed ->
            assertNull(
                "NONE room with hasFailed=$hasFailed must resolve to no write",
                resolveRoomSendStatus(ROOM_SEND_STATUS_NONE, hasFailed)
            )
        }
    }

    @Test
    fun `resolveRoomSendStatus reports SENDING as stale once the failure is gone`() {
        
        
        assertEquals(ROOM_SEND_STATUS_NONE, resolveRoomSendStatus(ROOM_SEND_STATUS_SENDING, hasFailed = false))
        
        
        assertEquals(ROOM_SEND_STATUS_FAILED, resolveRoomSendStatus(ROOM_SEND_STATUS_SENDING, hasFailed = true))
    }

    
    
    
    @Test
    fun `resolveRoomSendStatus guards NONE independently of the recompute gate`() {
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        assertNull(resolveRoomSendStatus(ROOM_SEND_STATUS_NONE, hasFailed = true))
    }

    
    @Test
    fun `roomSendStatusBackfillPlan splits into flag and clear sets`() {
        val (toFlag, toClear) = roomSendStatusBackfillPlan(
            roomsWithFailedMessage = setOf("r1", "r2"),
            roomsCurrentlyFlagged = setOf("r2", "r3"),
        )
        assertEquals(listOf("r1"), toFlag)
        assertEquals(listOf("r3"), toClear)
    }

    @Test
    fun `roomSendStatusBackfillPlan edge cases`() {
        
        roomSendStatusBackfillPlan(setOf("r1", "r2"), emptySet()).let { (flag, clear) ->
            assertEquals(setOf("r1", "r2"), flag.toSet())
            assertTrue(clear.isEmpty())
        }
        
        roomSendStatusBackfillPlan(emptySet(), setOf("r1", "r2")).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertEquals(setOf("r1", "r2"), clear.toSet())
        }
        
        roomSendStatusBackfillPlan(setOf("r1"), setOf("r1")).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertTrue(clear.isEmpty())
        }
        
        roomSendStatusBackfillPlan(emptySet(), emptySet()).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertTrue(clear.isEmpty())
        }
    }
}
