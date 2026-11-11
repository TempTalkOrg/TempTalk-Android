package com.difft.android.chat.recent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test










class ChatListTagGeometryTest {

    private val density = 3f

    
    private val fixedReserveDp = 68 + 28 + 4 + 16 + 4

    private fun width(
        rowWidthPx: Int = 1080,
        hasUnreadBadge: Boolean = false,
        isLargerText: Boolean = false,
        hasSendingIcon: Boolean = false,
        hasPinnedIcon: Boolean = false,
    ): Float = computeTagAvailableWidthPx(
        rowWidthPx = rowWidthPx,
        density = density,
        hasUnreadBadge = hasUnreadBadge,
        isLargerText = isLargerText,
        hasSendingIcon = hasSendingIcon,
        hasPinnedIcon = hasPinnedIcon,
    )

    

    @Test
    fun `T4-12 baseline row reserves only the fixed chrome plus the preview floor`() {
        assertEquals(1080f - density * (fixedReserveDp + 56), width(), 0f)
        assertEquals(552f, width(), 0f)
    }

    

    @Test
    fun `T4-13 an unread badge costs 24dp`() {
        assertEquals(width() - density * 24, width(hasUnreadBadge = true), 0f)
    }

    @Test
    fun `a pinned icon costs 18dp`() {
        assertEquals(width() - density * 18, width(hasPinnedIcon = true), 0f)
    }

    

    @Test
    fun `T4-15 larger text raises both the preview floor and the badge reserve`() {
        
        assertEquals(width() - density * 28, width(isLargerText = true), 0f)
        
        assertEquals(
            width() - density * 28 - density * 30,
            width(isLargerText = true, hasUnreadBadge = true),
            0f,
        )
    }

    

    @Test
    fun `T4-18 the sending icon costs 20dp regardless of text scale`() {
        
        assertEquals(width() - density * 20, width(hasSendingIcon = true), 0f)
        assertEquals(
            width(isLargerText = true) - density * 20,
            width(isLargerText = true, hasSendingIcon = true),
            0f,
        )
    }

    

    @Test
    fun `T4-16 the worst combination legitimately returns a negative budget`() {
        val worst = width(
            rowWidthPx = 720,
            hasUnreadBadge = true,
            isLargerText = true,
            hasSendingIcon = true,
        )
        
        assertEquals(720f - density * (fixedReserveDp + 84 + 30 + 20), worst, 0f)
        assertTrue("expected a negative budget, was $worst", worst < 0f)
    }

    

    @Test
    fun `T4-17 row width falls back item view then container then display`() {
        assertEquals(900, resolveRowWidthPx(900, 1000, 1080))
        assertEquals(1000, resolveRowWidthPx(0, 1000, 1080))
        assertEquals(1080, resolveRowWidthPx(0, 0, 1080))
    }
}
