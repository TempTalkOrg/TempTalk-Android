package com.difft.android.call.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StringUtilTest {

    @Test
    fun `short string is unchanged`() {
        assertEquals("Alice", StringUtil.truncateWithEllipsis("Alice", 14))
    }

    @Test
    fun `exact grapheme count is unchanged`() {
        assertEquals("Alice", StringUtil.truncateWithEllipsis("Alice", 5))
        assertEquals("A👩‍💻", StringUtil.truncateWithEllipsis("A👩‍💻", 2))
    }

    @Test
    fun `longer string retains N graphemes followed by three dots`() {
        assertEquals("Ali...", StringUtil.truncateWithEllipsis("Alice", 3))
    }

    @Test
    fun `surrogate pair at the cut is kept whole or dropped whole`() {
        assertEquals("A😀...", StringUtil.truncateWithEllipsis("A😀B", 2))
        assertEquals("A...", StringUtil.truncateWithEllipsis("A😀B", 1))
    }

    @Test
    fun `ZWJ emoji at the cut is kept whole or dropped whole`() {
        assertEquals("A👩‍💻...", StringUtil.truncateWithEllipsis("A👩‍💻B", 2))
        assertEquals("A...", StringUtil.truncateWithEllipsis("A👩‍💻B", 1))
        assertEquals("👨‍👩‍👧‍👦...", StringUtil.truncateWithEllipsis("👨‍👩‍👧‍👦B", 1))
    }

    @Test
    fun `emoji modifiers and flags remain attached`() {
        assertEquals("A👍🏽...", StringUtil.truncateWithEllipsis("A👍🏽B", 2))
        assertEquals("A👩🏽‍💻...", StringUtil.truncateWithEllipsis("A👩🏽‍💻B", 2))
        assertEquals("🇺🇸...", StringUtil.truncateWithEllipsis("🇺🇸🇨🇳", 1))
    }

    @Test
    fun `combining mark stays with its base character`() {
        assertEquals("Ae\u0301...", StringUtil.truncateWithEllipsis("Ae\u0301B", 2))
    }

    @Test
    fun `empty text is unchanged for every limit`() {
        for (limit in listOf(14, 0, -1)) {
            assertEquals("", StringUtil.truncateWithEllipsis("", limit))
        }
    }

    @Test
    fun `nonpositive limits preserve nonempty text`() {
        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals("Alice", StringUtil.truncateWithEllipsis("Alice", limit))
        }
    }
}
