package com.difft.android.call

import androidx.compose.ui.unit.dp
import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import com.difft.android.call.ui.portraitBottomReserved
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the call-chrome geometry block of [LCallUiConstants] and for the three
 * consumer-side composites derived from it.
 *
 * Tier 1: plain JUnit only. The subjects are an `object` of `const val Int` plus three
 * top-level `Dp` vals, so no Robolectric / MockK / Hilt infrastructure is warranted.
 *
 * These rows are the value-preservation core of the chrome-constant consolidation: the
 * literals they pin are the numbers that used to be hand-copied across
 * `MainPageWithTopStatusView`, `MainPageWithBottomControlView`, `BarrageMessageView` and
 * `PortraitParticipantLayout`. A "cleanup" that changed any rendered magnitude cannot land
 * green here.
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

    // -----------------------------------------------------------------------------------
    // TC22 — the compact reserve's derivation. `PORTRAIT_TOP_BAR_HEIGHT_COMPACT` stays
    // private, so this row pins the 52 it re-derives from the shared primitives; together
    // with PortraitTopReserveTest's `portraitTopReserved(7, true) == 52.dp` it makes a
    // silent 52 -> 64 (+12dp) shift inside a cleanup commit unlandable green.
    // -----------------------------------------------------------------------------------
    @Test
    fun `compact top reserve derives to fifty-two`() {
        assertEquals(
            52,
            LCallUiConstants.TOP_BAR_MARGIN_TOP_DP + LCallUiConstants.TOP_BAR_HEIGHT_DP
        )
    }

    // -----------------------------------------------------------------------------------
    // TC23 — the grid's bottom reserve, read from PRODUCTION's own function per bar plan.
    //
    // Reading `portraitBottomReserved` rather than re-deriving `chromeBottomReserveDp + 8`
    // keeps the row sensitive to production dropping the gap from its own expression.
    // -----------------------------------------------------------------------------------
    @Test
    fun `portrait grid reserve follows the bar plan`() {
        // Phone-width group grid: 48 margin + 48 bar + 56 outside Emoji + 8 gap.
        val phone = CallActionBarPlanner.resolve(375, 812, isGroup = true, isLandscape = false)
        assertEquals(160.dp, portraitBottomReserved(phone))
        // Wide portrait window (split layout): 48 margin + 48 bar + 8 gap.
        val wide = CallActionBarPlanner.resolve(704, 932, isGroup = true, isLandscape = false)
        assertEquals(104.dp, portraitBottomReserved(wide))
        // Compact: 48 margin + 40 bar + 56 outside Emoji + 8 gap.
        val compact = CallActionBarPlanner.resolve(300, 310, isGroup = true, isLandscape = false)
        assertEquals(152.dp, portraitBottomReserved(compact))
    }
}
