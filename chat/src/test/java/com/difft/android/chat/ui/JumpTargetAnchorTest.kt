package com.difft.android.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test





class JumpTargetAnchorTest {

    private var clock = 0L
    private val anchor = JumpTargetAnchor(now = { clock }, holdMs = 1_000)

    @Test
    fun `growth right after a jump re-applies it`() {
        anchor.set(42)
        clock = 300

        assertEquals(42, anchor.reanchorOnLayout(delta = -800))
    }

    @Test
    fun `nothing without a pending jump, after a drag, or once expired`() {
        assertNull(anchor.reanchorOnLayout(delta = -800))

        anchor.set(42)
        anchor.clear() 
        assertNull(anchor.reanchorOnLayout(delta = -800))

        anchor.set(42)
        clock = 1_500
        assertNull(anchor.reanchorOnLayout(delta = -800))
    }

    @Test
    fun `a shrink right after a jump re-applies it too (a call strip above the list)`() {
        anchor.set(42)
        clock = 300

        assertEquals(42, anchor.reanchorOnLayout(delta = 121))
        assertEquals("the jump is still pending", 42, anchor.reanchorOnLayout(delta = -800))
    }

    @Test
    fun `a shrink without a pending jump is left to the list's bottom anchoring`() {
        assertNull(anchor.reanchorOnLayout(delta = 121))

        anchor.set(42)
        anchor.clear() 
        assertNull(anchor.reanchorOnLayout(delta = 121))

        anchor.set(42)
        clock = 1_500
        assertNull("expired", anchor.reanchorOnLayout(delta = 121))
    }

    @Test
    fun `an unchanged height is no re-anchor`() {
        anchor.set(42)
        assertNull(anchor.reanchorOnLayout(delta = 0))
    }
}
