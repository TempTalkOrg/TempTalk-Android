package com.difft.android.chat.group

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupExpiryUtilsTest {
    @Test
    fun `only crypto upgrade and account invalid notifies are excluded`() {
        assertFalse(shouldClearGroupExpiryOnNotify(48))
        assertFalse(shouldClearGroupExpiryOnNotify(999))
        for (type in listOf(-1, 0, 6, 28, 35, 47, 49, 1000)) {
            assertTrue(shouldClearGroupExpiryOnNotify(type), "type=$type")
        }
    }

    @Test
    fun `days round up at the exact day boundary`() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals(1, groupDissolveDaysLeft(now + 1, now))
        assertEquals(1, groupDissolveDaysLeft(now + day - 1, now))
        assertEquals(1, groupDissolveDaysLeft(now + day, now))
        assertEquals(2, groupDissolveDaysLeft(now + day + 1, now))
        assertEquals(2, groupDissolveDaysLeft(now + 2 * day, now))
    }

    @Test
    fun `deadline today and in the past never produce negative days`() {
        assertEquals(0, groupDissolveDaysLeft(100, 100))
        assertEquals(0, groupDissolveDaysLeft(99, 100))
        assertEquals(1, groupDissolveDaysLeft(0, -1))
    }

    @Test
    fun `large timestamps retain a one millisecond remainder without overflowing`() {
        assertEquals(1, groupDissolveDaysLeft(Long.MAX_VALUE, Long.MAX_VALUE - 1))
        assertEquals(Int.MAX_VALUE, groupDissolveDaysLeft(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
