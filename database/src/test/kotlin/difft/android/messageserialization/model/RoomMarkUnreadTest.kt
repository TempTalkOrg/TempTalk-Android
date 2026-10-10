package difft.android.messageserialization.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules behind the manual "mark as unread" state: when a stored mark is still visible, and
 * how an incoming sync from another device applies against the stored mark and read position.
 */
class RoomMarkUnreadTest {

    @Test
    fun `mark is active while nothing newer happened and it is not read past`() {
        assertTrue(isManualUnreadActive(MARK_UNREAD_FLAG_UNREAD, markUnreadAt = 1_000, lastActiveTime = 900, readPosition = 900))
    }

    @Test
    fun `mark is inactive when flag is CLEAR or READ`() {
        assertFalse(isManualUnreadActive(MARK_UNREAD_FLAG_CLEAR, markUnreadAt = 1_000, lastActiveTime = 900, readPosition = 900))
        assertFalse(isManualUnreadActive(MARK_UNREAD_FLAG_READ, markUnreadAt = 1_000, lastActiveTime = 900, readPosition = 900))
    }

    @Test
    fun `mark is superseded by a message newer than the mark time`() {
        assertFalse(isManualUnreadActive(MARK_UNREAD_FLAG_UNREAD, markUnreadAt = 1_000, lastActiveTime = 1_001, readPosition = 900))
    }

    @Test
    fun `mark is superseded once the read position reaches the mark time`() {
        assertFalse(isManualUnreadActive(MARK_UNREAD_FLAG_UNREAD, markUnreadAt = 1_000, lastActiveTime = 900, readPosition = 1_000))
    }

    @Test
    fun `mark without a mark time is never active`() {
        assertFalse(isManualUnreadActive(MARK_UNREAD_FLAG_UNREAD, markUnreadAt = 0, lastActiveTime = 0, readPosition = 0))
    }

    @Test
    fun `incoming sync older than the stored mark is ignored`() {
        assertNull(resolveIncomingMarkUnread(MARK_UNREAD_FLAG_UNREAD, incomingAt = 500, storedAt = 1_000, readPosition = 0))
        assertNull(resolveIncomingMarkUnread(MARK_UNREAD_FLAG_READ, incomingAt = 1_000, storedAt = 1_000, readPosition = 0))
    }

    @Test
    fun `incoming UNREAD already read past on this device stores CLEAR`() {
        assertEquals(
            MARK_UNREAD_FLAG_CLEAR,
            resolveIncomingMarkUnread(MARK_UNREAD_FLAG_UNREAD, incomingAt = 1_000, storedAt = 0, readPosition = 1_000)
        )
    }

    @Test
    fun `incoming flags newer than the stored mark are stored as received`() {
        assertEquals(MARK_UNREAD_FLAG_UNREAD, resolveIncomingMarkUnread(MARK_UNREAD_FLAG_UNREAD, incomingAt = 1_000, storedAt = 0, readPosition = 900))
        assertEquals(MARK_UNREAD_FLAG_READ, resolveIncomingMarkUnread(MARK_UNREAD_FLAG_READ, incomingAt = 1_000, storedAt = 900, readPosition = 2_000))
        assertEquals(MARK_UNREAD_FLAG_CLEAR, resolveIncomingMarkUnread(MARK_UNREAD_FLAG_CLEAR, incomingAt = 1_000, storedAt = 900, readPosition = 0))
    }
}
