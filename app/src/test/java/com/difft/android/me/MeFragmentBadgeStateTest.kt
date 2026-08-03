package com.difft.android.me

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure badge-state rule: hidden at null/0, raw count with no cap. No Fragment/Android needed. */
class MeFragmentBadgeStateTest {

    @Test
    fun `C1t count 2 shows badge with text 2`() {
        val (visible, text) = linkedDevicesBadgeState(2)
        assertTrue(visible)
        assertEquals("2", text)
    }

    @Test
    fun `C2t count 0 hides badge`() {
        val (visible, text) = linkedDevicesBadgeState(0)
        assertFalse(visible)
        assertEquals("", text)
    }

    @Test
    fun `C3t count null hides badge`() {
        val (visible, text) = linkedDevicesBadgeState(null)
        assertFalse(visible)
        assertEquals("", text)
    }

    @Test
    fun `C4t count 12 shows badge with no cap`() {
        val (visible, text) = linkedDevicesBadgeState(12)
        assertTrue(visible)
        assertEquals("12", text)
    }
}
