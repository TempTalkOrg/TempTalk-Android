package com.difft.android.base.widget.sideBar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideBarLayoutTest {

    private val min = 12f

    @Test
    fun `all letters get their own slot when they fit`() {
        val slots = SideBarLayout.slots(letterCount = 3, available = 400f, minItemHeight = min)

        assertEquals(List(3) { SideBarSlot.Letter(it) }, slots)
    }

    @Test
    fun `condensed rail alternates letters and dots and keeps both ends`() {
        // 36 / 12 = 3 slots for 27 letters.
        val slots = SideBarLayout.slots(letterCount = 27, available = 36f, minItemHeight = min)

        assertEquals(listOf(SideBarSlot.Letter(0), SideBarSlot.Dot(1, 25), SideBarSlot.Letter(26)), slots)
    }

    @Test
    fun `condensed rail covers every letter exactly once`() {
        for (fit in 3..26) {
            val slots = SideBarLayout.slots(letterCount = 27, available = fit * min, minItemHeight = min)
            val covered = slots.flatMap {
                when (it) {
                    is SideBarSlot.Letter -> listOf(it.index)
                    is SideBarSlot.Dot -> (it.fromIndex..it.toIndex).toList()
                }
            }
            assertEquals("fit=$fit", (0..26).toList(), covered)
            assertTrue("fit=$fit", slots.size <= fit)
            assertTrue("fit=$fit", slots.first() is SideBarSlot.Letter && slots.last() is SideBarSlot.Letter)
        }
    }

    @Test
    fun `rail is hidden when fewer than three slots fit`() {
        assertEquals(emptyList<SideBarSlot>(), SideBarLayout.slots(27, available = 35f, minItemHeight = min))
        assertEquals(emptyList<SideBarSlot>(), SideBarLayout.slots(27, available = 0f, minItemHeight = min))
        assertEquals(emptyList<SideBarSlot>(), SideBarLayout.slots(0, available = 400f, minItemHeight = min))
    }

    @Test
    fun `tapping a visible letter in a condensed rail selects that letter`() {
        val slots = SideBarLayout.slots(letterCount = 27, available = 36f, minItemHeight = min)

        assertEquals(0, SideBarLayout.letterIndexAt(slots, slotHeight = 12f, y = 6f))
        assertEquals(26, SideBarLayout.letterIndexAt(slots, slotHeight = 12f, y = 30f))
    }

    @Test
    fun `dragging across a dot visits each hidden letter`() {
        val slots = SideBarLayout.slots(letterCount = 27, available = 36f, minItemHeight = min)
        val visited = (0 until 1200).map { step ->
            SideBarLayout.letterIndexAt(slots, slotHeight = 12f, y = 12f + step * 12f / 1200)
        }.distinct()

        assertEquals((1..25).toList(), visited)
    }

    @Test
    fun `touches outside the rail clamp to the ends`() {
        val slots = List(5) { SideBarSlot.Letter(it) }

        assertEquals(0, SideBarLayout.letterIndexAt(slots, slotHeight = 16f, y = -40f))
        assertEquals(4, SideBarLayout.letterIndexAt(slots, slotHeight = 16f, y = 500f))
        assertEquals(-1, SideBarLayout.letterIndexAt(emptyList(), slotHeight = 16f, y = 10f))
    }
}
