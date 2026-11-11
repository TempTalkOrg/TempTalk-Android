package com.difft.android.chat.focus

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusOwnedMarkTest {

    @Test
    fun `a value round-trips for its owner only`() {
        val raw = FocusOwnedMark.encode("me", "0:A,B")
        assertEquals("0:A,B", FocusOwnedMark.decode(raw, "me"))
        assertNull(FocusOwnedMark.decode(raw, "someone-else"))
        assertNull(FocusOwnedMark.decode(raw, "m")) 
        assertNull(FocusOwnedMark.decode(raw, null))
        assertNull(FocusOwnedMark.decode(raw, ""))
    }

    @Test
    fun `an unstamped, empty or malformed value is never anyone's mark`() {
        assertNull(FocusOwnedMark.decode(null, "me"))
        assertNull(FocusOwnedMark.decode("", "me"))
        assertNull(FocusOwnedMark.decode("A", "me"))
        assertNull(FocusOwnedMark.decode("|A", "me"))
        assertNull(FocusOwnedMark.decode("me|", "me"))
    }

    @Test
    fun `a value may itself contain the separator`() {
        assertEquals("a|b", FocusOwnedMark.decode(FocusOwnedMark.encode("me", "a|b"), "me"))
    }
}
