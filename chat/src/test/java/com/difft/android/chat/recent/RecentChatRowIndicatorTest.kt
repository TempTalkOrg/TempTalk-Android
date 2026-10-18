package com.difft.android.chat.recent

import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned-row indicator and background rules of the chat list row. */
class RecentChatRowIndicatorTest {

    private fun showPin(
        isForSearch: Boolean = false,
        isPinned: Boolean = true,
        displayUnreadNum: Int = 0,
    ) = shouldShowPinnedIcon(isForSearch, isPinned, displayUnreadNum)

    @Test
    fun `pinned row with a free trailing slot shows the pin`() {
        assertTrue(showPin())
    }

    @Test
    fun `unpinned row never shows the pin`() {
        assertFalse(showPin(isPinned = false))
    }

    @Test
    fun `unread count or manual-unread dot takes the slot before the pin`() {
        // displayUnreadNum is 1 for a manual-unread dot and the real count otherwise.
        assertFalse(showPin(displayUnreadNum = 1))
        assertFalse(showPin(displayUnreadNum = 120))
    }

    @Test
    fun `search results hide the pin`() {
        assertFalse(showPin(isForSearch = true))
    }

    private fun background(isSelected: Boolean = false, isPinned: Boolean = false, hasCallBar: Boolean = false) =
        rowBackgroundColorRes(isSelected, isPinned, hasCallBar)

    @Test
    fun `plain row uses bg1 and pinned row is tinted bg2`() {
        assertEquals(R.color.bg1, background())
        assertEquals(R.color.bg2, background(isPinned = true))
    }

    @Test
    fun `dual-pane selection uses bg3 over pinned and active-call backgrounds`() {
        assertEquals(R.color.bg3, background(isSelected = true))
        assertEquals(R.color.bg3, background(isSelected = true, isPinned = true))
        assertEquals(R.color.bg3, background(isSelected = true, hasCallBar = true))
        assertEquals(R.color.bg2, background(hasCallBar = true))
    }
}
