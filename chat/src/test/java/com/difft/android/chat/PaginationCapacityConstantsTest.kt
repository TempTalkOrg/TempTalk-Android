package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.INITIAL_PAGE_SIZE
import com.difft.android.chat.ChatNormalPaginationController.Companion.JUMP_PAGE_SIZE
import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.SCROLL_PAGE_SIZE
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_HIGH_WATER
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_SLACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Cases #72 and #73 — the four capacity constants and the two derived trim marks.
 *
 * These are asserted as VALUES on purpose, unlike every other case in this suite: this is the one
 * place that pins what the numbers actually are, so re-tuning any of them has to come here and be
 * deliberate. Everywhere else references the symbols.
 */
class PaginationCapacityConstantsTest {

    // #72 — the four are independent symbols, and the window cap is not a multiple of any page size.
    // It used to be literally `3 * PAGE_SIZE`, which is what coupled "how many rows a load fetches"
    // to "how many rows the window may hold".
    @Test
    fun `the four capacity constants hold their tuned values and stay decoupled`() {
        assertEquals(20L, INITIAL_PAGE_SIZE)
        assertEquals(50L, SCROLL_PAGE_SIZE)
        assertEquals(20L, JUMP_PAGE_SIZE)
        assertEquals(180, MAX_MESSAGE_COUNT)
        assertNotEquals(
            "the window cap must not be derived from a page size again",
            (3 * SCROLL_PAGE_SIZE).toInt(),
            MAX_MESSAGE_COUNT,
        )
    }

    // #73 — the trim band is derived from the cap, never re-stated. A second literal here is how the
    // hysteresis silently stops scaling with the window.
    @Test
    fun `the trim marks are derived from the window cap`() {
        assertEquals(90, TRIM_SLACK)
        assertEquals(270, TRIM_HIGH_WATER)
        assertEquals(MAX_MESSAGE_COUNT / 2, TRIM_SLACK)
        assertEquals(MAX_MESSAGE_COUNT + MAX_MESSAGE_COUNT / 2, TRIM_HIGH_WATER)
    }
}
