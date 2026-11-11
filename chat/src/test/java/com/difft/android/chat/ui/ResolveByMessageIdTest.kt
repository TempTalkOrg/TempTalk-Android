package com.difft.android.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test





class ResolveByMessageIdTest {

    @Test
    fun H8_positionHit_returnsReQueriedItem() {
        val currentList = listOf("a", "b", "c")
        var itemAtCalledWith = -1
        val result = resolveByMessageId(
            messageId = "b",
            indexOf = { id -> currentList.indexOf(id) },
            itemAt = { pos -> itemAtCalledWith = pos; "view@$pos" }
        )
        
        assertEquals(1, itemAtCalledWith)
        assertEquals("view@1", result)
    }

    @Test
    fun H9_positionMiss_returnsNull_andItemAtNotCalled() {
        val currentList = listOf("a", "b", "c")
        var itemAtCalled = false
        val result = resolveByMessageId<String>(
            messageId = "missing",
            indexOf = { id -> currentList.indexOf(id) }, 
            itemAt = { itemAtCalled = true; "should-not-happen" }
        )
        assertNull(result)
        assertFalse(itemAtCalled)
    }
}
