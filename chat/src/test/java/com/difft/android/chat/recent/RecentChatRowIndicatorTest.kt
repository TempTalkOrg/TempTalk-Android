package com.difft.android.chat.recent

import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Trailing-slot indicator and background rules of the chat list row. */
class RecentChatRowIndicatorTest {

    private fun indicator(
        isForSearch: Boolean = false,
        isFocusCard: Boolean = false,
        isPinned: Boolean = true,
        displayUnreadNum: Int = 0,
    ) = trailingIndicator(isForSearch, isFocusCard, isPinned, displayUnreadNum)

    @Test
    fun `pinned row with a free trailing slot shows the pin`() {
        assertEquals(TrailingIndicator.PIN, indicator())
    }

    @Test
    fun `unpinned plain row shows nothing`() {
        assertEquals(TrailingIndicator.NONE, indicator(isPinned = false))
    }

    @Test
    fun `unread count or manual-unread dot takes the slot before every icon`() {
        // displayUnreadNum is 1 for a manual-unread dot and the real count otherwise.
        assertEquals(TrailingIndicator.UNREAD, indicator(displayUnreadNum = 1))
        assertEquals(TrailingIndicator.UNREAD, indicator(displayUnreadNum = 120))
        assertEquals(TrailingIndicator.UNREAD, indicator(isFocusCard = true, displayUnreadNum = 3))
    }

    @Test
    fun `the focus row shows the focus icon instead of the pin, pinned or not`() {
        assertEquals(TrailingIndicator.FOCUS, indicator(isFocusCard = true, isPinned = true))
        assertEquals(TrailingIndicator.FOCUS, indicator(isFocusCard = true, isPinned = false))
    }

    @Test
    fun `search results show only the unread badge`() {
        assertEquals(TrailingIndicator.NONE, indicator(isForSearch = true))
        assertEquals(TrailingIndicator.NONE, indicator(isForSearch = true, isFocusCard = true))
        assertEquals(TrailingIndicator.UNREAD, indicator(isForSearch = true, displayUnreadNum = 2))
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
