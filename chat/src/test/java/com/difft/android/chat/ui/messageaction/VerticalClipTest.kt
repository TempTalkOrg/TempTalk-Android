package com.difft.android.chat.ui.messageaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1-G2 (#1294): the shared handle-visibility predicate, plain JVM. */
class VerticalClipTest {

    @Test
    fun `G1 intersects is exclusive at both edges`() {
        val clip = VerticalClip(100, 200)
        assertFalse(clip.intersects(50, 100))
        assertTrue(clip.intersects(50, 101))
        assertTrue(clip.intersects(199, 250))
        assertFalse(clip.intersects(200, 250))
    }

    @Test
    fun `G2 UNBOUNDED intersects every span`() {
        val clip = VerticalClip.UNBOUNDED
        assertTrue(clip.intersects(Int.MIN_VALUE + 1, Int.MIN_VALUE + 2))
        assertTrue(clip.intersects(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1))
        assertTrue(clip.intersects(-100_000, 100_000))
        assertTrue(clip.intersects(0, 1))
    }
}
