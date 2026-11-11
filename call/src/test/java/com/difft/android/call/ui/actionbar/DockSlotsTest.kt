package com.difft.android.call.ui.actionbar

import com.difft.android.call.ui.tabletop.TabletopGeometry
import org.junit.Assert.assertEquals
import org.junit.Test


class DockSlotsTest {

    private val dock = TabletopGeometry(bandTopPx = 1127, bandBottomPx = 1237, widthPx = 1672, heightPx = 2364, density = 2.75f)

    @Test
    fun `group dock shows People where the panel can render`() {
        assertEquals(
            listOf(
                listOf(DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER, DockSlot.EMOJI),
                listOf(DockSlot.INVITE, DockSlot.PEOPLE, DockSlot.MORE, DockSlot.END),
            ),
            dockSlots(isGroup = true, peopleAvailable = true),
        )
        assertEquals(
            listOf(DockSlot.INVITE, DockSlot.MORE, DockSlot.END),
            dockSlots(isGroup = true, peopleAvailable = false)[1],
        )
    }

    @Test
    fun `1v1 dock has no People control`() {
        assertEquals(
            listOf(
                listOf(DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER, DockSlot.EMOJI),
                listOf(DockSlot.INVITE, DockSlot.MORE, DockSlot.END),
            ),
            dockSlots(isGroup = false, peopleAvailable = true),
        )
        assertEquals(7, dockSlots(isGroup = false, peopleAvailable = true).flatten().size)
        assertEquals(8, dockSlots(isGroup = true, peopleAvailable = true).flatten().size)
    }

    @Test
    fun `More quick actions are empty on the dock and unchanged otherwise`() {
        val plan = CallActionBarPlanner.resolve(608, 860, isGroup = true, isLandscape = false)
        for (isGroup in listOf(true, false)) {
            for (share in listOf(true, false)) {
                assertEquals(emptyList<ActionBarQuickAction>(), moreQuickActions(CallChromeLayout(plan, null, dock), isGroup, share))
                assertEquals(plan.moreQuickActions, moreQuickActions(CallChromeLayout(plan, null), isGroup, share))
            }
        }
        val rail = resolveSideRail(860f, 608f)!!
        val railPlan = CallActionBarPlanner.resolve(860, 608, isGroup = true, isLandscape = true)
        assertEquals(
            railMoreQuickActions(isGroup = true, isScreenShare = true, fitsSevenControls = rail.fitsSevenControls),
            moreQuickActions(CallChromeLayout(railPlan, rail), isGroup = true, isScreenShare = true),
        )
    }
}
