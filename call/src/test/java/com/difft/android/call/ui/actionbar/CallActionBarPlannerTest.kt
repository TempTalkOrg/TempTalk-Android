package com.difft.android.call.ui.actionbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the design's width budgets and its device lookup table, expressed purely as window
 * sizes so the planner never needs to know which device it is running on.
 */
class CallActionBarPlannerTest {

    private fun plan(w: Int, h: Int, group: Boolean, landscape: Boolean = w > h) =
        CallActionBarPlanner.resolve(w, h, group, landscape)

    @Test
    fun `width budgets match the spec table`() {
        assertEquals(480, CallActionBarPlanner.splitNeed(isGroup = false))
        assertEquals(600, CallActionBarPlanner.splitNeed(isGroup = true))
        assertEquals(348, CallActionBarPlanner.singleRowNeed(5, 48, 12, isGroup = false))
        assertEquals(378, CallActionBarPlanner.singleRowNeed(5, 48, 12, isGroup = true))
        assertEquals(288, CallActionBarPlanner.singleRowNeed(4, 48, 12, isGroup = false))
        assertEquals(318, CallActionBarPlanner.singleRowNeed(4, 48, 12, isGroup = true))
        assertEquals(232, CallActionBarPlanner.singleRowNeed(4, 40, 8, isGroup = false))
        assertEquals(257, CallActionBarPlanner.singleRowNeed(4, 40, 8, isGroup = true))
        assertEquals(78, CallActionBarPlanner.groupEndWidth(48))
        assertEquals(65, CallActionBarPlanner.groupEndWidth(40))
    }

    @Test
    fun `phone portrait - 1v1 two rows, group emoji outside`() {
        assertEquals(ActionBarLayout.TWO_ROW, plan(375, 812, group = false).layout)
        assertEquals(ActionBarLayout.EMOJI_OUTSIDE, plan(375, 812, group = true).layout)
        // Wider phones are still tall, so 1v1 keeps its labelled two rows.
        assertEquals(ActionBarLayout.TWO_ROW, plan(411, 914, group = false).layout)
        // 16:9 phones (1.78) are the shortest "phone" aspect and must still get two rows.
        assertEquals(ActionBarLayout.TWO_ROW, plan(360, 640, group = false).layout)
        assertEquals(ActionBarLayout.EMOJI_OUTSIDE, plan(360, 640, group = true).layout)
    }

    @Test
    fun `squat cover screen - both modes fall to one full row`() {
        assertEquals(ActionBarLayout.FULL_ROW, plan(476, 752, group = false).layout)
        assertEquals(ActionBarLayout.FULL_ROW, plan(476, 752, group = true).layout)
    }

    @Test
    fun `unfolded inner screen - split in portrait and landscape`() {
        assertEquals(ActionBarLayout.SPLIT, plan(704, 932, group = false).layout)
        assertEquals(ActionBarLayout.SPLIT, plan(704, 932, group = true).layout)
        assertEquals(ActionBarLayout.SPLIT, plan(932, 704, group = true, landscape = true).layout)
    }

    @Test
    fun `tiny near-square window - compact`() {
        assertEquals(ActionBarLayout.COMPACT, plan(300, 310, group = false).layout)
        assertEquals(ActionBarLayout.COMPACT, plan(300, 310, group = true).layout)
        val compact = plan(300, 310, group = true)
        assertEquals(40, compact.buttonSizeDp)
        assertEquals(20, compact.iconSizeDp)
        assertEquals(8, compact.gapDp)
        assertEquals(65, compact.endButtonWidthDp)
    }

    @Test
    fun `landscape share never uses two rows`() {
        val p = plan(812, 375, group = false, landscape = true)
        assertEquals(ActionBarLayout.SPLIT, p.layout)
        assertEquals(20, p.bottomMarginDp)
    }

    @Test
    fun `bottom margins follow layout`() {
        assertEquals(44, plan(375, 812, group = false).bottomMarginDp)
        assertEquals(48, plan(375, 812, group = true).bottomMarginDp)
        assertEquals(20, plan(932, 704, group = true, landscape = true).bottomMarginDp)
    }

    @Test
    fun `more sheet quick actions are the controls the bar dropped`() {
        assertEquals(listOf(ActionBarQuickAction.INVITE), plan(375, 812, group = false).moreQuickActions)
        assertEquals(
            listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.PEOPLE),
            plan(375, 812, group = true).moreQuickActions,
        )
        assertEquals(listOf(ActionBarQuickAction.INVITE), plan(476, 752, group = false).moreQuickActions)
        assertTrue(plan(704, 932, group = true).moreQuickActions.isEmpty())
    }

    @Test
    fun `split shows invite always and people only for groups`() {
        val oneOnOne = plan(704, 932, group = false)
        assertTrue(oneOnOne.showInvite)
        assertFalse(oneOnOne.showPeople)
        val group = plan(704, 932, group = true)
        assertTrue(group.showInvite)
        assertTrue(group.showPeople)
    }

    @Test
    fun `chrome reserve stacks margin, bar and outside emoji`() {
        // Group phone: 48 margin + 48 bar + (40 + 16) outside emoji.
        assertEquals(152, plan(375, 812, group = true).chromeBottomReserveDp)
        // 1v1 phone two rows with the video backplate: 28 + 16 + 176 + 16.
        assertEquals(236, plan(375, 812, group = false).chromeBottomReserveDp)
        // Full row: no outside emoji.
        assertEquals(96, plan(476, 752, group = true).chromeBottomReserveDp)
    }
}
