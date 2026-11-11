package com.difft.android.chat.search

import org.junit.Test
import kotlin.test.assertEquals


class SearchSnippetTest {

    @Test
    fun `a match deep in a long message keeps the eight characters before it after an ellipsis`() {
        assertEquals("…12345678needle and more", searchSnippet("a long preamble 12345678needle and more", "needle"))
    }

    @Test
    fun `a match within the first eight characters keeps the whole text`() {
        assertEquals("12345678needle", searchSnippet("12345678needle", "needle"))
        assertEquals("1234567needle", searchSnippet("1234567needle", "NEEDLE"))
    }

    @Test
    fun `line breaks fold into spaces before the cut is measured`() {
        assertEquals("one two  three", searchSnippet("one\ntwo\r\nthree", "two"))
        assertEquals("…ine  ab two", searchSnippet("long line\n\nab two", "two"))
    }

    @Test
    fun `matching ignores case and an empty key leaves the text whole`() {
        assertEquals("…abcdefghKEY", searchSnippet("0123456789abcdefghKEY", "key"))
        assertEquals("no key here", searchSnippet("no key here", ""))
    }

    @Test
    fun `the cut never splits an emoji before the match`() {
        val text = "padding text 😀1234567key"
        
        assertEquals("…😀1234567key", searchSnippet(text, "key"))
    }
}
