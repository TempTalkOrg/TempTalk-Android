package com.difft.android.call.ui.actionbar

import com.difft.android.call.layout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class BookActionBarTest {

    @Test
    fun `the gap is centred on the fold line and 32dp wider than the fold, not its padding`() {
        
        val split = bookBarSplit(Rect(x = 395.0, y = 0.0, w = 50.0, h = 860.0))
        assertEquals(420.0 - 21.0, split.leftEndDp, 1e-9)
        assertEquals(420.0 + 21.0, split.rightStartDp, 1e-9)
    }

    @Test
    fun `a line fold leaves 32dp`() {
        val split = bookBarSplit(Rect(x = 588.0, y = 0.0, w = 40.0, h = 860.0))
        assertEquals(32.0, split.rightStartDp - split.leftEndDp, 1e-9)
    }

    @Test
    fun `each group's budget is its side of the gap less the inset`() {
        
        val split = bookBarSplit(Rect(x = 180.0, y = 0.0, w = 40.0, h = 860.0))
        val (left, right) = bookBarBudgets(split, windowWidthDp = 608.0, insetDp = 16.0)
        assertEquals(200.0 - 16.0 - 16.0, left, 1e-9)
        assertEquals(608.0 - 16.0 - 216.0, right, 1e-9)
    }

    @Test
    fun `a group wider than its budget shrinks to fit, one that fits keeps its size`() {
        
        assertEquals(168f / 240f, bookGroupScale(240, 168), 1e-6f)
        assertEquals(1f, bookGroupScale(240, 400), 0f)
        assertEquals(1f, bookGroupScale(0, 0), 0f)
    }

    @Test
    fun `hang-up is last on the right, media and emoji on the left`() {
        val group = CallActionBarPlanner.resolve(1216, 860, isGroup = true, isLandscape = true)
        val (left, right) = bookBarGroups(group)
        assertEquals(DockSlot.END, right.last())
        assertTrue(left.containsAll(listOf(DockSlot.EMOJI, DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER)))
        assertFalse(DockSlot.END in left)
        assertTrue(DockSlot.MORE in right)

        val oneOnOne = CallActionBarPlanner.resolve(1216, 860, isGroup = false, isLandscape = true)
        assertFalse("no people button in a 1v1", DockSlot.PEOPLE in bookBarGroups(oneOnOne).second)
    }
}
