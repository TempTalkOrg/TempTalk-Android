package com.difft.android.call.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [portraitTopReserved], [portraitTopFollowsTitleBar] and [portraitCenteredTop] —
 * the pure functions that decide how much space the portrait participant grid leaves above its
 * first row, whether that space follows the title bar's show/hide, and where the fixed ≤6 block
 * is placed vertically.
 *
 * Tier 1: plain JUnit only. Every subject is a pure function over `Int`/`Boolean`/[Dp], so no
 * Robolectric / MockK / Hilt infrastructure is warranted.
 *
 * The dp literals below are the *pinned contract values*, not a re-derivation of the
 * production branch: every case calls the production function and compares its result to
 * the number the design fixes. `PORTRAIT_TOP_BAR_HEIGHT_COMPACT` is deliberately private,
 * so its 52dp is pinned transitively through `portraitTopReserved(7, topVisible = true)`.
 */
class PortraitTopReserveTest {

    // -----------------------------------------------------------------------------------
    // TC1 — 7+ scrolling gallery, title shown: the compact reserve.
    // -----------------------------------------------------------------------------------
    @Test
    fun `seven people with the title shown reserve the compact top bar height`() {
        assertEquals(52.dp, portraitTopReserved(count = 7, topVisible = true))
    }

    // -----------------------------------------------------------------------------------
    // TC2 — 7+ scrolling gallery, title hidden: the fix's core value (#1128).
    // The grid keeps only the status-bar inset, so the black band is gone.
    // -----------------------------------------------------------------------------------
    @Test
    fun `seven people with the title hidden reserve nothing`() {
        assertEquals(0.dp, portraitTopReserved(count = 7, topVisible = false))
    }

    // -----------------------------------------------------------------------------------
    // TC3 — 5 and 6 people (the top-aligned fixed grid) behave like the gallery.
    // -----------------------------------------------------------------------------------
    @Test
    fun `five and six people follow the same reserve in both states`() {
        for (count in 5..6) {
            assertEquals(
                "count=$count, title shown",
                52.dp,
                portraitTopReserved(count = count, topVisible = true)
            )
            assertEquals(
                "count=$count, title hidden",
                0.dp,
                portraitTopReserved(count = count, topVisible = false)
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // TC4 — 2..4 people (the centred branch) keep the full top-bar reserve.
    // Regression lock: this branch was explicitly scoped out of the responsive fix.
    // -----------------------------------------------------------------------------------
    @Test
    fun `two to four people reserve the full top bar height plus the content gap`() {
        for (count in 2..4) {
            assertEquals(
                "count=$count",
                64.dp,
                portraitTopReserved(count = count, topVisible = true)
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // TC5 — totality: the function must answer for every count when the title is hidden,
    // even for the counts that never pass `false` in production.
    // -----------------------------------------------------------------------------------
    @Test
    fun `one to four people reserve nothing when the title is hidden`() {
        for (count in 1..4) {
            assertEquals(
                "count=$count",
                0.dp,
                portraitTopReserved(count = count, topVisible = false)
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // TC6 — the untouched-path gate. PiP never follows the title bar, and neither do the
    // single-participant or ≤4 centred layouts.
    // -----------------------------------------------------------------------------------
    @Test
    fun `only the non-PiP layouts above four people follow the title bar`() {
        val counts = listOf(1, 2, 3, 4, 5, 6, 7, 8, 15)

        for (count in counts) {
            assertFalse(
                "PiP must never follow the title bar (count=$count)",
                portraitTopFollowsTitleBar(count = count, forceScrollGrid = true)
            )
        }

        for (count in listOf(1, 2, 3, 4)) {
            assertFalse(
                "count=$count is centred or single — nothing to reclaim",
                portraitTopFollowsTitleBar(count = count, forceScrollGrid = false)
            )
        }

        for (count in listOf(5, 6, 7, 8, 15)) {
            assertTrue(
                "count=$count is top-aligned and must follow the title bar",
                portraitTopFollowsTitleBar(count = count, forceScrollGrid = false)
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // TC31 — portraitCenteredTop, slack-present regime: the block is centred on the FULL
    // height and the bottom clamp is inert. Numbers mirror the 4-person block at
    // w360dp-h740dp (160dp square cells, so width-bound with real slack below).
    // -----------------------------------------------------------------------------------
    @Test
    fun `a block with slack below it is centred on the full height`() {
        // Centre = (740 - 328) / 2 = 206; the clamp would allow up to 740 - 140 - 328 = 272.
        assertEquals(
            206.dp,
            portraitCenteredTop(
                availableHeight = 740.dp,
                contentHeight = 328.dp,
                minTop = 106.dp,
                bottomReserve = 140.dp,
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // TC32 — portraitCenteredTop, height-bound regime: the bottom clamp binds, so the block
    // never overlaps the reserve. This is the case the unclamped centring got wrong — the
    // last row rendered under the floating control bar and barrage entry.
    // -----------------------------------------------------------------------------------
    @Test
    fun `a height bound block is clamped so its bottom edge clears the bottom reserve`() {
        // 6 people at w360dp-h740dp: 160dp width-bound cells, contentHeight 496.
        // Unclamped centre = (740 - 496) / 2 = 122, which overlaps by 18dp.
        // Clamp = 740 - 140 - 496 = 104, still clear of the 94dp top-chrome floor.
        assertEquals(
            104.dp,
            portraitCenteredTop(
                availableHeight = 740.dp,
                contentHeight = 496.dp,
                minTop = 94.dp,
                bottomReserve = 140.dp,
            )
        )

        // 6 people at w360dp-h520dp: height-bound cells fill the whole band between the bars
        // (contentHeight 286 = 520 - 94 - 140), so the clamp lands exactly on the top-chrome
        // floor and the centring degrades to between-the-bars placement.
        assertEquals(
            94.dp,
            portraitCenteredTop(
                availableHeight = 520.dp,
                contentHeight = 286.dp,
                minTop = 94.dp,
                bottomReserve = 140.dp,
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // TC33 — portraitCenteredTop totality: minTop wins last, so a degenerate window whose
    // reserves exceed its own height still yields the top chrome rather than a negative or
    // overlapping offset. Production cannot reach this (the caller sizes the block against
    // the band between the bars), but the function must stay total.
    // -----------------------------------------------------------------------------------
    @Test
    fun `the top chrome floor wins when the reserves exceed the window height`() {
        assertEquals(
            94.dp,
            portraitCenteredTop(
                availableHeight = 300.dp,
                contentHeight = 286.dp,
                minTop = 94.dp,
                bottomReserve = 140.dp,
            )
        )
    }
}
