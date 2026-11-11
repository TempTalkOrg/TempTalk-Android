package com.difft.android.chat.group

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class GroupCryptoOperationsTest {

    @After
    fun tearDown() {
        GroupCryptoOperations.end("g1")
        GroupCryptoOperations.end("g2")
    }

    @Test
    fun `a second reset is refused while the first is in flight, across a page recreation`() {
        
        assertTrue(GroupCryptoOperations.tryBegin("g1"))

        
        assertTrue("g1" in GroupCryptoOperations.running.value)
        assertFalse(GroupCryptoOperations.tryBegin("g1"))

        
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
