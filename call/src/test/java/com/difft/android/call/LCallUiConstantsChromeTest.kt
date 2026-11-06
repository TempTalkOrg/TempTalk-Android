package com.difft.android.call

import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the call-chrome geometry block of [LCallUiConstants].
 *
 * Tier 1: plain JUnit only. The subject is an `object` of `const val Int`, so no Robolectric /
 * MockK / Hilt infrastructure is warranted.
 *
 * These rows are the value-preservation core of the chrome-constant consolidation: the
 * literals they pin are the numbers that used to be hand-copied across
 * `MainPageWithTopStatusView`, `MainPageWithBottomControlView` and `BarrageMessageView`. A
 * "cleanup" that changed any rendered magnitude cannot land green here.
 */
class LCallUiConstantsChromeTest {

    // -----------------------------------------------------------------------------------
    // TC20 — chrome primitives, each verified against the producer site it replaced.
    // -----------------------------------------------------------------------------------
    @Test
    fun `chrome primitives hold their producer-site values`() {
        // MainPageWithTopStatusView: bar height and the two column margins around it.
        assertEquals(52, LCallUiConstants.TOP_BAR_HEIGHT_DP)
        assertEquals(0, LCallUiConstants.TOP_BAR_MARGIN_TOP_DP)
        assertEquals(4, LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP)

        // Action bar: the full-size control diameter the planner derives everything else from.
        assertEquals(48, LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP)
        assertEquals(LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP, CallActionBarPlanner.BUTTON_DP)

        // The breathing gap shared by grid content and the chrome bars.
        assertEquals(8, LCallUiConstants.CHROME_CONTENT_GAP_DP)
    }

    // -----------------------------------------------------------------------------------
    // TC21 — the derived top total that replaced the hand-copied 56 literal.
    // -----------------------------------------------------------------------------------
    @Test
    fun `derived chrome totals equal the literals they replaced`() {
        assertEquals(56, LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP)
    }
}
