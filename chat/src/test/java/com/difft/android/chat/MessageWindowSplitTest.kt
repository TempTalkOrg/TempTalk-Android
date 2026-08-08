package com.difft.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [splitMessageWindow] — pure generic function, no Robolectric / WCDB / MockK.
 *
 * Doubles as the regression net for the extraction: the three branches asserted here are the exact
 * shapes the two inline copies in [ChatNormalPaginationController] produced before they were
 * replaced by a single call.
 */
class MessageWindowSplitTest {

    private val pageSize = 20

    private fun ints(size: Int): List<Int> = (0 until size).toList()

    // --- T6-8: three-branch shape ---

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

    /**
     * Shape test for the both-anchors branch. The loaders' LIMIT arithmetic caps the candidate list
     * at pageSize + 1, so this branch is unreachable from them today — it is asserted because the
     * extraction must preserve the inline algorithm branch for branch, not because production hits it.
     */
    @Test
    fun `mixed input with two spare elements yields both anchors`() {
        val input = ints(22)
        val window = splitMessageWindow(input, forwardCount = 5, pageSize = pageSize)

        assertEquals(0, window.anchorBefore)
        assertEquals(input.subList(1, 21), window.pageMessages)
        assertEquals(21, window.anchorAfter)
    }

    // --- T6-9: partition invariants over the whole input domain ---

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

                    // The before-anchor is always the element immediately preceding the page.
                    window.anchorBefore?.let {
                        assertEquals("$case: before anchor is not adjacent", input[start - 1], it)
                    }
                    if (start > 0) {
                        assertEquals("$case: dropped a leading element without an anchor", input[start - 1], window.anchorBefore)
                    }

                    // The after-anchor is always the input's last element.
                    window.anchorAfter?.let {
                        assertEquals("$case: after anchor is not the last input element", input.last(), it)
                    }
                    // It is adjacent to the page only over the domain the loaders can actually
                    // produce, which is size <= pageSize + 1: every call site issues a forward query
                    // of LIMIT pageSize + 1 and tops up backwards only when that returned A <
                    // pageSize rows, with LIMIT pageSize - A + 1 — so the candidate list never
                    // exceeds pageSize + 1. Above that bound the forward-overflow branch keeps the
                    // input's last element as the after-anchor without it being page-adjacent.
                    if (size <= pageSize + 1) {
                        window.anchorAfter?.let {
                            assertEquals("$case: after anchor is not adjacent", input[start + page.size], it)
                        }
                    }
                }
            }
        }
    }

    // --- T6-10: anchor preservation under the failure-anchored loader's construction rules ---

    @Test
    fun `failure anchored window always keeps the failed message and any close unread`() {
        // Mirrors loadFirstScreenAnchoredAtFailure: forward query of LIMIT pageSize + 1 from the
        // failure, and — only when it returned fewer than pageSize rows — a backward top-up of
        // LIMIT pageSize - A + 1. Forward elements are 0..A-1 (index 0 IS the failed message),
        // earlier elements are negative so ascending order is the list order.
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
