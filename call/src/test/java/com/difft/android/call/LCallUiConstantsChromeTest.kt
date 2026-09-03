package com.difft.android.call

import androidx.compose.ui.unit.dp
import com.difft.android.call.ui.PORTRAIT_BARRAGE_ENTRY_RESERVED
import com.difft.android.call.ui.PORTRAIT_BOTTOM_RESERVED
import com.difft.android.call.ui.barrage.barrageStackBottomPadding
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

        // MainPageWithBottomControlView: control diameter and the portrait bottom margin.
        assertEquals(48, LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP)
        assertEquals(32, LCallUiConstants.BOTTOM_BAR_MARGIN_BOTTOM_DP)

        // BarrageMessageView entry button: icon size and its four-sided padding.
        assertEquals(20, LCallUiConstants.BARRAGE_ENTRY_ICON_SIZE_DP)
        assertEquals(12, LCallUiConstants.BARRAGE_ENTRY_PADDING_DP)

        // The breathing gap shared by grid content and the chrome bars.
        assertEquals(8, LCallUiConstants.CHROME_CONTENT_GAP_DP)
    }

    // -----------------------------------------------------------------------------------
    // TC21 — the derived totals that replaced hand-copied 56 / 80 literals, plus the
    // barrage entry's intrinsic height (previously implicit inside the 52dp reserve).
    // -----------------------------------------------------------------------------------
    @Test
    fun `derived chrome totals equal the literals they replaced`() {
        assertEquals(56, LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP)
        assertEquals(80, LCallUiConstants.BOTTOM_BAR_TOTAL_HEIGHT_DP)
        assertEquals(44, LCallUiConstants.BARRAGE_ENTRY_TOTAL_HEIGHT_DP)
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
    // TC23 — the consumer composites, read from PRODUCTION's own vals.
    //
    // These assertions deliberately read `PORTRAIT_BOTTOM_RESERVED`,
    // `PORTRAIT_BARRAGE_ENTRY_RESERVED` and `barrageStackBottomPadding` instead of
    // recomputing them from `LCallUiConstants`. A test-side re-derivation such as
    // `BOTTOM_BAR_TOTAL_HEIGHT_DP + CHROME_CONTENT_GAP_DP == 88` would stay green if
    // production later dropped the gap from its own expression — while the grid and the
    // barrage stack silently moved 8dp.
    // -----------------------------------------------------------------------------------
    @Test
    fun `consumer composites hold their pinned values`() {
        assertEquals(140.dp, PORTRAIT_BOTTOM_RESERVED)
        assertEquals(52.dp, PORTRAIT_BARRAGE_ENTRY_RESERVED)
        assertEquals(88.dp, barrageStackBottomPadding)
    }
}
