package com.difft.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test








class MessageWindowSplitTest {

    private val pageSize = 20

    private fun ints(size: Int): List<Int> = (0 until size).toList()

    

    @Test
    fun `short input yields no anchors`() {
        val input = ints(15)
        val window = splitMessageWindow(input, forwardCount = 15, pageSize = pageSize)

        assertNull(window.anchorBefore)
        assertEquals(input, window.pageMessages)
        assertNull(window.anchorAfter)
    }

    @Test
    fun `forward query overflowing the page yields only an after anchor`() {
        val input = ints(21)
        val window = splitMessageWindow(input, forwardCount = 21, pageSize = pageSize)

        assertNull(window.anchorBefore)
        assertEquals(input.take(20), window.pageMessages)
        assertEquals(20, window.anchorAfter)
    }

    @Test
    fun `mixed input with exactly one spare element yields only a before anchor`() {
        val input = ints(21)
        val window = splitMessageWindow(input, forwardCount = 5, pageSize = pageSize)

        assertEquals(0, window.anchorBefore)
        assertEquals(input.subList(1, 21), window.pageMessages)
        assertNull(window.anchorAfter)
    }

    




    @Test
    fun `mixed input with two spare elements yields both anchors`() {
        val input = ints(22)
        val window = splitMessageWindow(input, forwardCount = 5, pageSize = pageSize)

        assertEquals(0, window.anchorBefore)
        assertEquals(input.subList(1, 21), window.pageMessages)
        assertEquals(21, window.anchorAfter)
    }

    

    @Test
    fun `partition invariants hold for every size and forward count`() {
        for (size in 0..25) {
            val input = ints(size)
            for (forwardCount in 0..size) {
                val case = "size=$size forwardCount=$forwardCount"
                val window = splitMessageWindow(input, forwardCount, pageSize)
                val page = window.pageMessages

                assertTrue("$case: page overflows pageSize", page.size <= pageSize)
                assertEquals("$case: page has duplicates", page.size, page.distinct().size)

                if (page.isEmpty()) {
                    assertTrue("$case: empty page only legal for empty input", input.isEmpty())
                } else {
                    val start = input.indexOf(page.first())
                    assertEquals("$case: page is not a contiguous slice", input.subList(start, start + page.size), page)

                    
                    window.anchorBefore?.let {
                        assertEquals("$case: before anchor is not adjacent", input[start - 1], it)
                    }
                    if (start > 0) {
                        assertEquals("$case: dropped a leading element without an anchor", input[start - 1], window.anchorBefore)
                    }

                    
                    window.anchorAfter?.let {
                        assertEquals("$case: after anchor is not the last input element", input.last(), it)
                    }
                    
                    
                    
                    
                    
                    
                    if (size <= pageSize + 1) {
                        window.anchorAfter?.let {
                            assertEquals("$case: after anchor is not adjacent", input[start + page.size], it)
                        }
                    }
                }
            }
        }
    }

    

    @Test
    fun `failure anchored window always keeps the failed message and any close unread`() {
        
        
        
        
        for (afterSize in 1..pageSize + 1) {
            val earlierSize = if (afterSize < pageSize) pageSize - afterSize + 1 else 0
            val earlier = (-earlierSize..-1).toList()
            val after = (0 until afterSize).toList()
            val sorted = earlier + after
            val case = "afterSize=$afterSize earlierSize=$earlierSize"

            val window = splitMessageWindow(sorted, forwardCount = afterSize, pageSize = pageSize)

            assertTrue("$case: failed message fell out of the page", window.pageMessages.contains(0))
            for (gap in 0 until minOf(afterSize, pageSize)) {
                assertTrue(
                    "$case: forward element at gap=$gap fell out of the page",
                    window.pageMessages.contains(gap)
                )
            }
        }
    }
}
