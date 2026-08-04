package difft.android.messageserialization.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure decision functions behind `RoomModel.sendStatus`.
 *
 * These carry ALL of the aggregate's decision logic on purpose: every WCDB-touching test in
 * :database is `@Ignore`-d (native `System.loadLibrary` is unavailable to JVM unit tests), so any
 * logic left inside a query function is effectively untested. Keeping it here makes it executable.
 *
 * Covers T3-1, T3-2, T3-6, T3-7, T3-8.
 */
class RoomSendStatusTest {

    // T3-2 — pins the numeric domain. 0 == NONE is a migration-safety requirement: the column
    // ships with @WCDBDefault(intValue = 0), so every pre-upgrade row reads back 0 and MUST mean
    // "no failure". Reusing SendType's domain (0 == Sending) would flag every historical room.
    @Test
    fun `status constants have the documented values`() {
        assertEquals(0, ROOM_SEND_STATUS_NONE)
        assertEquals(1, ROOM_SEND_STATUS_SENDING)
        assertEquals(2, ROOM_SEND_STATUS_FAILED)
    }

    // T3-1 — aggregate semantics: failure outranks in-flight.
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

    // T3-6 — the gate that keeps the write path at zero extra queries for NONE rooms (which is
    // every room, almost always) and is why no `sendType` index is needed.
    @Test
    fun `needsSendStatusRecompute only admits already-flagged rooms`() {
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        assertTrue(needsSendStatusRecompute(ROOM_SEND_STATUS_SENDING))
        assertTrue(needsSendStatusRecompute(ROOM_SEND_STATUS_FAILED))
    }

    // T3-7 — clear-side resolution. `null` means "issue no write at all".
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

    // T3-7 (core) — the recompute NEVER escalates to FAILED. Discovering a new failure is the
    // set-source's job (PushTextSendJob / cold-start sweep / one-shot backfill); if this ever
    // returns FAILED for a NONE room, the "clear only" invariant is broken.
    @Test
    fun `resolveRoomSendStatus never escalates a NONE room to FAILED`() {
        // Exhaustive over hasFailed: a NONE room always resolves to "no write". This is what makes
        // the clear-side recompute unable to discover a failure, so a NONE room stays at zero
        // queries and zero writes per incoming message.
        listOf(true, false).forEach { hasFailed ->
            assertNull(
                "NONE room with hasFailed=$hasFailed must resolve to no write",
                resolveRoomSendStatus(ROOM_SEND_STATUS_NONE, hasFailed)
            )
        }
    }

    @Test
    fun `resolveRoomSendStatus reports SENDING as stale once the failure is gone`() {
        // A SENDING room is never produced this release, but the resolution must still be defined:
        // no failure means NONE, so the stale SENDING is cleared rather than kept.
        assertEquals(ROOM_SEND_STATUS_NONE, resolveRoomSendStatus(ROOM_SEND_STATUS_SENDING, hasFailed = false))
        // With a failure present it escalates SENDING -> FAILED. That is a same-direction
        // transition on an ALREADY-FLAGGED room, so it is not the forbidden NONE escalation.
        assertEquals(ROOM_SEND_STATUS_FAILED, resolveRoomSendStatus(ROOM_SEND_STATUS_SENDING, hasFailed = true))
    }

    // The NONE guard must live in the function, not only in the caller's gate: the gate is a cost
    // filter, per the KDoc on [needsSendStatusRecompute] — so relying on it for this invariant would
    // make any future loosening of the gate silently turn the recompute into a set source.
    @Test
    fun `resolveRoomSendStatus guards NONE independently of the recompute gate`() {
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        assertNull(resolveRoomSendStatus(ROOM_SEND_STATUS_NONE, hasFailed = true))
    }

    // T3-8 — one-shot backfill is pure set arithmetic, so the upgrade path is testable without WCDB.
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
        // Nothing stored yet: flag everything, clear nothing.
        roomSendStatusBackfillPlan(setOf("r1", "r2"), emptySet()).let { (flag, clear) ->
            assertEquals(setOf("r1", "r2"), flag.toSet())
            assertTrue(clear.isEmpty())
        }
        // Nothing failing any more: clear everything, flag nothing.
        roomSendStatusBackfillPlan(emptySet(), setOf("r1", "r2")).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertEquals(setOf("r1", "r2"), clear.toSet())
        }
        // Already reconciled: no writes at all.
        roomSendStatusBackfillPlan(setOf("r1"), setOf("r1")).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertTrue(clear.isEmpty())
        }
        // Both empty (fresh install).
        roomSendStatusBackfillPlan(emptySet(), emptySet()).let { (flag, clear) ->
            assertTrue(flag.isEmpty())
            assertTrue(clear.isEmpty())
        }
    }
}
