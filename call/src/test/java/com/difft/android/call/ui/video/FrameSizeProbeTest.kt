package com.difft.android.call.ui.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSizeProbeTest {

    private val posted = mutableListOf<Runnable>()
    private val sizes = mutableListOf<Triple<Int, Int, Long>>()
    private var now = 1L
    private val probe = FrameSizeProbe(target = null, post = { posted += it }, clock = { now }) { w, h, t -> sizes += Triple(w, h, t) }

    private fun deliver() {
        posted.forEach { it.run() }
        posted.clear()
    }

    @Test
    fun `reports a size once per change with the time it was seen`() {
        probe.report(720, 1280)
        now = 2
        probe.report(720, 1280)
        probe.report(1280, 720)
        deliver()
        assertEquals(listOf(Triple(720, 1280, 1L), Triple(1280, 720, 2L)), sizes)
    }

    @Test
    fun `a reset reports the same size again`() {
        probe.report(720, 1280)
        probe.resetDedupe()
        probe.report(720, 1280)
        deliver()
        assertEquals(2, sizes.size)
    }

    @Test
    fun `closing drops sizes not yet delivered`() {
        probe.report(720, 1280)
        probe.close()
        probe.report(1280, 720)
        deliver()
        assertEquals(emptyList<Triple<Int, Int, Long>>(), sizes)
    }

    @Test
    fun `empty sizes are ignored`() {
        probe.report(0, 1280)
        deliver()
        assertEquals(emptyList<Triple<Int, Int, Long>>(), sizes)
    }
}
