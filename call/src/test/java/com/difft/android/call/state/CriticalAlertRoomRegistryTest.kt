package com.difft.android.call.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CriticalAlertRoomRegistry] (#1387): alerts are recorded per room; ending a room hands back its
 * ids once, remembers the room as ended (for [CriticalAlertRoomRegistry.TTL_MS]) even when nothing
 * was recorded, and leaves other rooms alone. Both sets are bounded.
 */
class CriticalAlertRoomRegistryTest {

    private var now = 1_000_000L
    private val registry = CriticalAlertRoomRegistry { now }

    @Test
    fun `ending a room returns the ids recorded for it and marks it ended`() {
        registry.record(ROOM, 1)
        registry.record(ROOM, 2)
        registry.record(ROOM, 1)

        assertFalse(registry.isEnded(ROOM))
        assertEquals(setOf(1, 2), registry.end(ROOM))
        assertTrue(registry.isEnded(ROOM))
    }

    @Test
    fun `ending is one-shot for the ids`() {
        registry.record(ROOM, 1)
        registry.end(ROOM)

        assertEquals(emptySet<Int>(), registry.end(ROOM))
        assertTrue(registry.isEnded(ROOM))
    }

    @Test
    fun `a room with nothing recorded is still marked ended`() {
        assertEquals(emptySet<Int>(), registry.end(ROOM))

        assertTrue(registry.isEnded(ROOM))
    }

    @Test
    fun `other rooms are untouched`() {
        registry.record(ROOM, 1)
        registry.record(OTHER_ROOM, 2)

        registry.end(ROOM)

        assertFalse(registry.isEnded(OTHER_ROOM))
        assertEquals(setOf(2), registry.end(OTHER_ROOM))
    }

    @Test
    fun `an ended room is forgotten after the ttl`() {
        registry.end(ROOM)

        now += CriticalAlertRoomRegistry.TTL_MS - 1
        assertTrue(registry.isEnded(ROOM))
        now += 1
        assertFalse(registry.isEnded(ROOM))
    }

    @Test
    fun `an alert recorded long ago is no longer returned`() {
        registry.record(ROOM, 1)
        now += CriticalAlertRoomRegistry.TTL_MS
        registry.record(ROOM, 2)

        assertEquals(setOf(2), registry.end(ROOM))
    }

    @Test
    fun `the ended set stays bounded and drops the oldest room first`() {
        repeat(200) { registry.end("room-$it") }

        assertFalse(registry.isEnded("room-0"))
        assertTrue(registry.isEnded("room-199"))
    }

    private companion object {
        const val ROOM = "room-1"
        const val OTHER_ROOM = "room-2"
    }
}
