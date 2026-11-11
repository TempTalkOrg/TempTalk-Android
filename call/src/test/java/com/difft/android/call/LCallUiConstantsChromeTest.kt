package com.difft.android.call

import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import org.junit.Assert.assertEquals
import org.junit.Test




class LCallUiConstantsChromeTest {

    @Test
    fun `chrome primitives hold their producer-site values`() {
        assertEquals(52, LCallUiConstants.TOP_BAR_HEIGHT_DP)
        assertEquals(0, LCallUiConstants.TOP_BAR_MARGIN_TOP_DP)
        assertEquals(4, LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP)

        assertEquals(48, LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP)
        assertEquals(LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP, CallActionBarPlanner.BUTTON_DP)

        assertEquals(8, LCallUiConstants.CHROME_CONTENT_GAP_DP)
    }

    @Test
    fun `derived chrome totals equal the literals they replaced`() {
        assertEquals(56, LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP)
    }
}
