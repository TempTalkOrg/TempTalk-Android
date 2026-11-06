package com.difft.android.call.ui.actionbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rail order and the More sheet's quick row while the rail is shown (#1451). */
class RailSlotsTest {

    private val head = listOf(RailSlot.MIC, RailSlot.VIDEO, RailSlot.SPEAKER)
    private val tail = listOf(RailSlot.MORE, RailSlot.END)

    @Test
    fun `group grid keeps Emoji on the rail, People in More`() {
        for (fits in listOf(false, true)) {
            assertEquals(head + RailSlot.EMOJI + tail, railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = fits))
            assertEquals(
                listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.PEOPLE),
                railMoreQuickActions(isGroup = true, isScreenShare = false, fitsSevenControls = fits),
            )
        }
    }

    @Test
    fun `group screen share with room shows People and Emoji on the rail`() {
        assertEquals(
            head + RailSlot.PEOPLE + RailSlot.EMOJI + tail,
            railSlots(isGroup = true, isScreenShare = true, fitsSevenControls = true),
        )
        assertEquals(
            listOf(ActionBarQuickAction.INVITE),
            railMoreQuickActions(isGroup = true, isScreenShare = true, fitsSevenControls = true),
        )
    }

    @Test
    fun `group screen share without room swaps People onto the rail and Emoji into More`() {
        assertEquals(head + RailSlot.PEOPLE + tail, railSlots(isGroup = true, isScreenShare = true, fitsSevenControls = false))
        assertEquals(
            listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.EMOJI),
            railMoreQuickActions(isGroup = true, isScreenShare = true, fitsSevenControls = false),
        )
    }

    @Test
    fun `1v1 keeps Emoji on the rail with or without a share`() {
        for (share in listOf(false, true)) {
            for (fits in listOf(false, true)) {
                assertEquals(head + RailSlot.EMOJI + tail, railSlots(isGroup = false, isScreenShare = share, fitsSevenControls = fits))
                assertEquals(
                    listOf(ActionBarQuickAction.INVITE),
                    railMoreQuickActions(isGroup = false, isScreenShare = share, fitsSevenControls = fits),
                )
            }
        }
    }

    @Test
    fun `rail and More are complementary, End last, Invite always in More`() {
        for (group in listOf(false, true)) {
            for (share in listOf(false, true)) {
                for (fits in listOf(false, true)) {
                    val slots = railSlots(group, share, fits)
                    val more = railMoreQuickActions(group, share, fits)
                    val expectedCount = if (group && share && fits) CallSideRailSpec.ROOMY_SLOT_COUNT else CallSideRailSpec.SLOT_COUNT
                    assertEquals(expectedCount, slots.size)
                    assertEquals(RailSlot.END, slots.last())
                    assertEquals(slots.toSet().size, slots.size)
                    assertEquals(ActionBarQuickAction.INVITE, more.first())
                    assertEquals(more.toSet().size, more.size)
                    // Emoji is always reachable exactly once; People too where a group has a panel.
                    val emojiPlaces = listOf(RailSlot.EMOJI in slots, ActionBarQuickAction.EMOJI in more).count { it }
                    assertEquals("emoji group=$group share=$share fits=$fits", 1, emojiPlaces)
                    val peoplePlaces = listOf(RailSlot.PEOPLE in slots, ActionBarQuickAction.PEOPLE in more).count { it }
                    assertEquals("people group=$group share=$share fits=$fits", if (group) 1 else 0, peoplePlaces)
                    assertTrue(RailSlot.MORE in slots)
                }
            }
        }
    }
}
