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








class PaginationCapacityConstantsTest {

    
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

    
    
    @Test
    fun `the trim marks are derived from the window cap`() {
        assertEquals(90, TRIM_SLACK)
        assertEquals(270, TRIM_HIGH_WATER)
        assertEquals(MAX_MESSAGE_COUNT / 2, TRIM_SLACK)
        assertEquals(MAX_MESSAGE_COUNT + MAX_MESSAGE_COUNT / 2, TRIM_HIGH_WATER)
    }
}
