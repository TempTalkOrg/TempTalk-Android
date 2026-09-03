package com.difft.android.chat.recent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4-12 … T4-18 — the width budget the tag run may spend, and the row-width fallback chain.
 *
 * Pure JVM. Every expected number is written out as an explicit dp sum so a change to any reserve
 * constant fails here with the arithmetic visible, rather than silently shifting how many tags
 * survive on a real device.
 *
 * Verify: :chat:testDebugUnitTest
 */
class ChatListTagGeometryTest {

    private val density = 3f

    /** avatar 64 + text column 28 + tag marginEnd 4 + detail marginEnd 16 + slack 4 = 116dp */
    private val fixedReserveDp = 64 + 28 + 4 + 16 + 4

    private fun width(
        rowWidthPx: Int = 1080,
        hasUnreadBadge: Boolean = false,
        hasCallBar: Boolean = false,
        isLargerText: Boolean = false,
        hasSendingIcon: Boolean = false,
    ): Float = computeTagAvailableWidthPx(
        rowWidthPx = rowWidthPx,
        density = density,
        hasUnreadBadge = hasUnreadBadge,
        hasCallBar = hasCallBar,
        isLargerText = isLargerText,
        hasSendingIcon = hasSendingIcon,
    )

    // ── T4-12 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-12 baseline row reserves only the fixed chrome plus the preview floor`() {
        assertEquals(1080f - density * (fixedReserveDp + 56), width(), 0f)
        assertEquals(564f, width(), 0f)
    }

    // ── T4-13 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-13 an unread badge costs 24dp`() {
        assertEquals(width() - density * 24, width(hasUnreadBadge = true), 0f)
    }

    // ── T4-14 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-14 an ongoing-call bar costs 88dp`() {
        assertEquals(width() - density * 88, width(hasCallBar = true), 0f)
    }

    // ── T4-15 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-15 larger text raises both the preview floor and the badge reserve`() {
        // preview floor 56 -> 84
        assertEquals(width() - density * 28, width(isLargerText = true), 0f)
        // badge 24 -> 30, on top of the raised preview floor
        assertEquals(
            width() - density * 28 - density * 30,
            width(isLargerText = true, hasUnreadBadge = true),
            0f,
        )
    }

    // ── T4-18 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-18 the sending icon costs 20dp regardless of text scale`() {
        // icon 16 + marginEnd 4; single tier — fixed-size icon, no LARGE variant
        assertEquals(width() - density * 20, width(hasSendingIcon = true), 0f)
        assertEquals(
            width(isLargerText = true) - density * 20,
            width(isLargerText = true, hasSendingIcon = true),
            0f,
        )
    }

    // ── T4-16 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-16 the worst combination legitimately returns a negative budget`() {
        val worst = width(
            rowWidthPx = 720,
            hasUnreadBadge = true,
            hasCallBar = true,
            isLargerText = true,
        )
        // 720 - 3 * (116 + 84 + 88 + 30) = -234
        assertEquals(720f - density * (fixedReserveDp + 84 + 88 + 30), worst, 0f)
        assertTrue("expected a negative budget, was $worst", worst < 0f)
    }

    // ── T4-17 ────────────────────────────────────────────────────────────────

    @Test
    fun `T4-17 row width falls back item view then container then display`() {
        assertEquals(900, resolveRowWidthPx(900, 1000, 1080))
        assertEquals(1000, resolveRowWidthPx(0, 1000, 1080))
        assertEquals(1080, resolveRowWidthPx(0, 0, 1080))
    }
}
