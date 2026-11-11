package com.difft.android.chat.recent

import org.junit.Test
import kotlin.test.assertEquals


class FocusCardLinesTest {

    @Test
    fun `the latest previews show oldest first`() {
        assertEquals(listOf("a: 1", "b: 2", "c: 3"), focusCardLines(draft = null, previews = listOf("a: 1", "b: 2", "c: 3"), fallback = "c: 3"))
    }

    @Test
    fun `fewer messages show fewer lines`() {
        assertEquals(listOf("a: 1"), focusCardLines(draft = null, previews = listOf("a: 1"), fallback = "a: 1"))
    }

    @Test
    fun `a draft takes the first line and the two newest previews follow`() {
        assertEquals(listOf("draft", "b: 2", "c: 3"), focusCardLines(draft = "draft", previews = listOf("a: 1", "b: 2", "c: 3"), fallback = null))
    }

    @Test
    fun `no previews fall back to the one-line preview`() {
        assertEquals(listOf<CharSequence?>("last"), focusCardLines(draft = null, previews = emptyList(), fallback = "last"))
        assertEquals(listOf<CharSequence?>("draft"), focusCardLines(draft = "draft", previews = emptyList(), fallback = "last"))
    }
}
