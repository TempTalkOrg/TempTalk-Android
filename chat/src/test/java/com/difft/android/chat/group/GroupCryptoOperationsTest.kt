package com.difft.android.chat.group

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide exclusion for group key rotation / encryption upgrade. It is not page state:
 * a group-info page recreated by a fold (a new fragment instance) sees the operation the old one
 * started and cannot start a second one until it ends.
 */
class GroupCryptoOperationsTest {

    @After
    fun tearDown() {
        GroupCryptoOperations.end("g1")
        GroupCryptoOperations.end("g2")
    }

    @Test
    fun `a second reset is refused while the first is in flight, across a page recreation`() {
        // Old page instance starts the rotation, then a fold destroys it.
        assertTrue(GroupCryptoOperations.tryBegin("g1"))

        // The recreated page reads the running state and is refused.
        assertTrue("g1" in GroupCryptoOperations.running.value)
        assertFalse(GroupCryptoOperations.tryBegin("g1"))

        // The outliving operation ends; a new one may start.
        GroupCryptoOperations.end("g1")
        assertFalse("g1" in GroupCryptoOperations.running.value)
        assertTrue(GroupCryptoOperations.tryBegin("g1"))
    }

    @Test
    fun `groups are independent`() {
        assertTrue(GroupCryptoOperations.tryBegin("g1"))
        assertTrue(GroupCryptoOperations.tryBegin("g2"))
        assertEquals(setOf("g1", "g2"), GroupCryptoOperations.running.value)
    }

    @Test
    fun `concurrent starts let exactly one through`() = runTest {
        val results = (1..50).map { GroupCryptoOperations.tryBegin("g1") }
        assertEquals(1, results.count { it })
    }
}
