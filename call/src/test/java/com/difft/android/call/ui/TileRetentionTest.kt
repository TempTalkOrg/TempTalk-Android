package com.difft.android.call.ui

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyed list [AnimatedTileLayout] draws: exits kept while they fade, returns reuse the node. */
class TileRetentionTest {

    private fun p(id: String, x: Int = 0) = TilePlacement(id, IntRect(x, 0, x + 10, 10), 0f)

    private fun List<RetainedPlacement>.ids() = map { it.placement.id }

    @Test
    fun `the first list neither enters nor departs`() {
        val drawn = TileRetention().update(listOf(p("a"), p("b")), snap = false)
        assertEquals(listOf("a", "b"), drawn.ids())
        assertTrue(drawn.none { it.entering || it.departing })
    }

    @Test
    fun `a tile that leaves stays drawn, departing, at its last place until it expires`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b", x = 20)), snap = false)
        val drawn = retention.update(listOf(p("a")), snap = false)
        assertEquals(listOf("a", "b"), drawn.ids())
        assertTrue(drawn.last().departing)
        assertEquals(IntRect(20, 0, 30, 10), drawn.last().placement.rect)

        assertTrue(retention.expire("b"))
        assertEquals(listOf("a"), retention.update(listOf(p("a")), snap = false, expired = 1).ids())
    }

    @Test
    fun `a tile that returns while fading is drawn again, neither entering nor departing`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        retention.update(listOf(p("a")), snap = false)
        val back = retention.update(listOf(p("a"), p("b", x = 5)), snap = false)
        assertEquals(listOf("a", "b"), back.ids())
        assertFalse(back.last().departing)
        assertFalse("the kept node fades back in, it is not new", back.last().entering)
        assertFalse("its fade can no longer drop it", retention.expire("b"))
    }

    @Test
    fun `a new tile enters, and one that expired enters again`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        assertTrue(retention.update(listOf(p("a"), p("b"), p("c")), snap = false).last().entering)
        retention.update(listOf(p("a"), p("c")), snap = false)
        retention.expire("b")
        assertTrue(retention.update(listOf(p("a"), p("c"), p("b")), snap = false, expired = 1).last().entering)
    }

    @Test
    fun `a snap drops a leaving tile at once and shows a new one without fading`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        val drawn = retention.update(listOf(p("a"), p("c")), snap = true)
        assertEquals(listOf("a", "c"), drawn.ids())
        assertTrue(drawn.none { it.entering || it.departing })
    }

    @Test
    fun `a tile dropped by a snap enters with its fade when it comes back`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        retention.update(listOf(p("a")), snap = true)
        assertTrue(retention.update(listOf(p("a"), p("b")), snap = false).last().entering)
    }

    @Test
    fun `a tile fading out when a snap comes is dropped and enters again later`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        retention.update(listOf(p("a")), snap = false)
        assertEquals(listOf("a", "c"), retention.update(listOf(p("a"), p("c")), snap = true).ids())
        assertTrue(retention.update(listOf(p("a"), p("c"), p("b")), snap = false).last().entering)
    }

    @Test
    fun `the same input gives the same list`() {
        val retention = TileRetention()
        retention.update(listOf(p("a"), p("b")), snap = false)
        val first = retention.update(listOf(p("a")), snap = false)
        assertTrue(first === retention.update(listOf(p("a")), snap = false))
    }
}
