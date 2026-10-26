package com.difft.android.base.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dot selection on a 3x3 grid in a 260px view (mdpi: 1px == 1dp): centres at 16 / 130 / 244 on both
 * axes, hit radius 24, start radius 72. Dot ids are `row * 3 + column`.
 */
class PatternSelectionTest {

    private val grid = PatternGrid(3).also { g ->
        for (id in 0 until g.size) {
            g.centerX[id] = 16f + (id % 3) * 114f
            g.centerY[id] = 16f + (id / 3) * 114f
        }
    }

    private fun selector() = PatternSelector(grid, hitRadius = 24f, startRadius = 72f, startSlideMaxDistance = 100f)

    private fun legacy() = LegacyPatternTracker(grid, hitRadius = 24f, startRadius = 72f, startSlideMaxDistance = 100f)

    private fun x(id: Int) = grid.centerX[id]

    private fun y(id: Int) = grid.centerY[id]

    private fun PatternSelector.downAt(id: Int) = down(x(id), y(id))

    private fun PatternSelector.moveTo(id: Int) = move(x(id), y(id))

    @Test
    fun `start picks the nearest dot when the start radius overlaps a neighbour`() {
        // 45px left of dot 1 is 69px from dot 0: both are within the 72px start radius.
        assertEquals(listOf(1), selector().down(x(1) - 45f, y(1)))

        val offGrid = selector()
        offGrid.down(-200f, -200f)
        assertEquals(listOf(1), offGrid.move(x(1) - 45f, y(1)))
    }

    @Test
    fun `T8 fast swipe past dot 2 selects it even when no sample lands on it`() {
        val selector = selector()
        selector.downAt(0)

        // The sample beside dot 1 is 26px from its centre (outside the 24px hit radius) but the segment
        // from the previous sample passes within 22px of it.
        val beside = selector.move(140f, 40f)
        val end = selector.moveTo(2)

        assertEquals(listOf(1), beside)
        assertEquals(listOf(2), end)
        assertEquals(listOf(0, 1, 2), selector.selected)
    }

    @Test
    fun `T8 a single long jump selects every dot on the segment in order`() {
        val selector = selector()
        selector.downAt(3)

        val added = selector.moveTo(5)

        assertEquals(listOf(4, 5), added)
        assertEquals(listOf(3, 4, 5), selector.selected)
    }

    @Test
    fun `T9 swiping over an already used middle dot accepts the jump and adds nothing else`() {
        val selector = selector()
        selector.downAt(1)
        selector.moveTo(0)

        val added = selector.moveTo(2)

        assertEquals(listOf(2), added)
        assertEquals(listOf(1, 0, 2), selector.selected)
    }

    @Test
    fun `T10 knight move selects only its two ends`() {
        val selector = selector()
        selector.downAt(0)

        val added = mutableListOf<Int>()
        for (step in 1..10) {
            val t = step / 10f
            added += selector.move(x(0) + (x(5) - x(0)) * t, y(0) + (y(5) - y(0)) * t)
        }

        assertEquals(listOf(5), added)
        assertEquals(listOf(0, 5), selector.selected)
    }

    @Test
    fun `middle dot is filled in a row when the path misses it but ends on the dot two cells away`() {
        val selector = selector()
        // Start acquired 44px below dot 0; the path to dot 2 passes 29px from dot 1, outside the hit radius.
        selector.down(16f, 60f)

        val added = selector.move(244f, 30f)

        assertEquals(listOf(1, 2), added)
        assertEquals(listOf(0, 1, 2), selector.selected)
    }

    @Test
    fun `middle dot is filled in a column`() {
        val selector = selector()
        selector.down(60f, 16f)

        val added = selector.move(30f, 244f)

        assertEquals(listOf(3, 6), added)
    }

    @Test
    fun `middle dot is filled on a diagonal`() {
        val selector = selector()
        selector.downAt(0)
        // Detour so the segment into dot 8 passes 31px from dot 4 (outside the 24px hit radius).
        assertEquals(emptyList<Int>(), selector.move(60f, 120f))

        val added = selector.moveTo(8)

        assertEquals(listOf(4, 8), added)
    }

    @Test
    fun `middle fill never re-adds a used middle dot`() {
        val selector = selector()
        selector.downAt(1)
        selector.moveTo(0)

        // 0 -> 2 with dot 1 already used.
        assertEquals(listOf(2), selector.move(244f, 30f))
        assertEquals(listOf(1, 0, 2), selector.selected)
    }

    @Test
    fun `T16 passing over a used dot adds nothing`() {
        val selector = selector()
        selector.downAt(0)
        selector.moveTo(1)

        // Back across dot 0 and 1 while staying on the row.
        assertTrue(selector.move(x(0), y(0)).isEmpty())
        assertTrue(selector.move(x(1), y(1)).isEmpty())
        assertTrue(selector.move(x(1) + 5f, y(1)).isEmpty())
        assertEquals(listOf(0, 1), selector.selected)
    }

    @Test
    fun `a touch that starts away from every dot selects nothing until the finger gets close`() {
        val selector = selector()
        assertTrue(selector.down(73f, 73f).isEmpty())

        assertEquals(listOf(4), selector.move(130f, 130f))
    }

    @Test
    fun `T13 legacy tracker reproduces the retired rule, starting 30dp off dot 1`() {
        val legacy = legacy()
        legacy.down(x(0), y(0) + 30f)
        legacy.move(x(2), y(2))
        legacy.move(x(8), y(8))
        legacy.move(x(7), y(7))

        assertEquals(listOf(0, 2, 8, 7), legacy.selected)
    }

    @Test
    fun `T13 legacy tracker ignores a dot the finger only crossed between samples`() {
        val legacy = legacy()
        legacy.down(x(0), y(0))
        legacy.move(140f, 40f)
        legacy.move(x(2), y(2))

        assertEquals(listOf(0, 2), legacy.selected)
    }

    @Test
    fun `legacy tracker starts from the 72dp radius and never selects a dot twice`() {
        val legacy = legacy()
        legacy.down(x(0) + 50f, y(0) + 50f) // 70.7 from dot 0
        legacy.move(x(1), y(1))
        legacy.move(x(0), y(0))
        legacy.move(x(1), y(1))

        assertEquals(listOf(0, 1), legacy.selected)
    }

    @Test
    fun `legacy tracker leaves a down point beyond 72dp unselected`() {
        val legacy = legacy()
        legacy.down(73f, 73f)

        assertTrue(legacy.selected.isEmpty())
    }
}
