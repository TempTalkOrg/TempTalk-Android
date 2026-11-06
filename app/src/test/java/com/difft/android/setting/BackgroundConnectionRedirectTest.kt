package com.difft.android.setting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which requirement the switch opens first, and when returning to the page turns the switch on. */
class BackgroundConnectionRedirectTest {

    private fun state(
        restricted: Boolean = false,
        ignoringBattery: Boolean = true,
        dataSaver: Boolean = false,
    ) = BgCheckState(isBackgroundRestricted = restricted, isIgnoringBatteryOpt = ignoringBattery, isDataSaverRestricted = dataSaver)

    @Test
    fun `nothing failing means the switch can turn on`() {
        assertNull(firstFailingRequirement(state()))
    }

    @Test
    fun `each requirement is found on its own`() {
        assertEquals(BgRequirement.BACKGROUND_RESTRICTION, firstFailingRequirement(state(restricted = true)))
        assertEquals(BgRequirement.BATTERY_OPTIMIZATION, firstFailingRequirement(state(ignoringBattery = false)))
        assertEquals(BgRequirement.DATA_SAVER, firstFailingRequirement(state(dataSaver = true)))
    }

    @Test
    fun `the first failing one wins in the manager's order`() {
        assertEquals(
            BgRequirement.BACKGROUND_RESTRICTION,
            firstFailingRequirement(state(restricted = true, ignoringBattery = false, dataSaver = true)),
        )
        assertEquals(BgRequirement.BATTERY_OPTIMIZATION, firstFailingRequirement(state(ignoringBattery = false, dataSaver = true)))
    }

    @Test
    fun `a requirement points at its own row`() {
        assertEquals(BgRow.BACKGROUND_RESTRICTION, BgRequirement.BACKGROUND_RESTRICTION.row)
        assertEquals(BgRow.BATTERY_OPTIMIZATION, BgRequirement.BATTERY_OPTIMIZATION.row)
        assertEquals(BgRow.DATA_SAVER, BgRequirement.DATA_SAVER.row)
    }

    @Test
    fun `returning after the switch turns it on once everything passes`() {
        assertEquals(ReturnAction.AutoEnable, returnAction(startedFromSwitch = true, alreadyOn = false, failing = null))
    }

    @Test
    fun `returning without having tapped the switch never turns it on`() {
        assertEquals(ReturnAction.None, returnAction(startedFromSwitch = false, alreadyOn = false, failing = null))
        assertEquals(
            ReturnAction.None,
            returnAction(startedFromSwitch = false, alreadyOn = false, failing = BgRequirement.DATA_SAVER),
        )
    }

    @Test
    fun `returning with a requirement still failing moves the highlight to it`() {
        assertEquals(
            ReturnAction.Highlight(BgRequirement.DATA_SAVER),
            returnAction(startedFromSwitch = true, alreadyOn = false, failing = BgRequirement.DATA_SAVER),
        )
    }

    @Test
    fun `an already running connection is left alone`() {
        assertEquals(ReturnAction.None, returnAction(startedFromSwitch = true, alreadyOn = true, failing = null))
    }
}
