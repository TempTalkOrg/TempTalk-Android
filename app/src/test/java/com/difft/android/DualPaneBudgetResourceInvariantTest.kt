package com.difft.android

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.WindowSizeClassUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drift guard for the dual-pane pane budget. Four things are pinned here, and nothing else
 * in the repo pins any of them:
 *
 * 1. **The feasibility invariant.** The `w673dp` component of the qualifier directory
 *    `app/src/main/res/layout-w673dp-h480dp/` — the resource gate that decides whether
 *    `activity_index.xml` inflates a `detail_pane` at all — MUST equal the sum of the four
 *    floor terms of the pane budget:
 *    `dual_pane_rail_width + dual_pane_divider_width + dual_pane_list_min_width +
 *    dual_pane_detail_min_width`. If they drift apart, dual-pane engages on a window that
 *    cannot satisfy its own declared minimums and `IndexActivity.applyListPaneWidth()`'s
 *    clamp silently starves one pane. [WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP] is the
 *    declared mirror of that directory name, so asserting against it catches a change to
 *    either side.
 * 2. **The two-file dimen layering with the band boundary at 900dp, not 840dp.** An unfolded
 *    Galaxy Z Fold 8 is exactly 840dp wide; on that 7.6" handheld the tablet budget (96dp
 *    rail + 360dp list) squeezes the conversation pane to 383dp, so 673-899dp windows must
 *    resolve the compact budget from `res/values/` and only >= 900dp windows the tablet
 *    budget from `res/values-w900dp/`. The w840dp and w899dp cases pin the deliberate part:
 *    Fold-8-class windows stay on the compact budget.
 * 3. **The drag floor sits above the static floor in every band.**
 *    `dual_pane_detail_drag_min_width` (360dp, band-independent) is the interactive clamp
 *    that keeps the 270dp fixed-width bubbles out of the clipping zone; the static
 *    `dual_pane_detail_min_width` may only go below it in the compact band, where the drag
 *    range is empty.
 * 4. **The runtime gate mirror sits on the same floor as the dimens.**
 *    `R.bool.dual_pane_layout_active` is carried by
 *    `app/src/main/res/values-w673dp-h480dp/bools.xml`, a THIRD directory whose qualifier
 *    digits must track the same floor as the feasibility sum above. The qualifier dp values
 *    are read FROM the constants rather than written as `@Config(qualifiers)` literals
 *    precisely so that a change to either constant fails here.
 *    [PaneGateResourceAgreementTest] pins the complementary half — bool against inflation.
 *
 * Dimen values are compared in dp via `getDimension(...) / density` rather than
 * `getDimensionPixelSize(...)` so the tablet band's 0.5dp divider is exact instead of
 * rounded.
 *
 * `application = Application::class` keeps this a pure resource test — the real
 * application Hilt graph is irrelevant to dimen resolution.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class DualPaneBudgetResourceInvariantTest {

    private class Budget(resources: Resources) {
        private val density = resources.displayMetrics.density
        val railDp = resources.getDimension(R.dimen.dual_pane_rail_width) / density
        val dividerDp = resources.getDimension(R.dimen.dual_pane_divider_width) / density
        val listDefaultDp = resources.getDimension(R.dimen.dual_pane_list_default_width) / density
        val listMinDp = resources.getDimension(R.dimen.dual_pane_list_min_width) / density
        val detailMinDp = resources.getDimension(R.dimen.dual_pane_detail_min_width) / density
        val detailDragMinDp =
            resources.getDimension(R.dimen.dual_pane_detail_drag_min_width) / density
        val floorSumDp = railDp + dividerDp + listMinDp + detailMinDp
    }

    private fun budget() = Budget(RuntimeEnvironment.getApplication().resources)

    @Test
    fun `default config carries the compact budget and its sum IS the qualifier floor`() {
        val budget = budget()

        assertEquals("rail (res/values/dimens.xml)", 72f, budget.railDp, TOLERANCE_DP)
        assertEquals("divider (res/values/dimens.xml)", 1f, budget.dividerDp, TOLERANCE_DP)
        assertEquals("list default (res/values/dimens.xml)", 280f, budget.listDefaultDp, TOLERANCE_DP)
        assertEquals("list minimum (res/values/dimens.xml)", 280f, budget.listMinDp, TOLERANCE_DP)
        assertEquals("detail minimum (res/values/dimens.xml)", 320f, budget.detailMinDp, TOLERANCE_DP)

        assertEquals(
            "rail + divider + list-min + detail-min MUST equal " +
                "WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP, which is the w673dp component of " +
                "app/src/main/res/layout-w673dp-h480dp/. Changing any minimum in " +
                "res/values/dimens.xml requires renaming that directory and updating the constant.",
            WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP.toFloat(),
            budget.floorSumDp,
            TOLERANCE_DP
        )
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp")
    fun `w900dp resolves the tablet budget`() {
        val budget = budget()

        // The geometry the dual-pane layout originally shipped with at the w840dp gate,
        // now applying only from 900dp up. Because these differ from the default config's
        // values above, this case also proves the LARGEST matching w<N>dp wins.
        assertEquals("rail (res/values-w900dp/dimens.xml)", 96f, budget.railDp, TOLERANCE_DP)
        assertEquals("divider (res/values-w900dp/dimens.xml)", 0.5f, budget.dividerDp, TOLERANCE_DP)
        assertEquals("list default (res/values-w900dp/dimens.xml)", 360f, budget.listDefaultDp, TOLERANCE_DP)
        assertEquals("list minimum (res/values-w900dp/dimens.xml)", 280f, budget.listMinDp, TOLERANCE_DP)
        assertEquals("detail minimum (res/values-w900dp/dimens.xml)", 360f, budget.detailMinDp, TOLERANCE_DP)

        assertEquals("tablet-band feasibility floor", 736.5f, budget.floorSumDp, TOLERANCE_DP)
        assertTrue(
            "the tablet budget must fit inside the band it applies to (${budget.floorSumDp}dp > 900dp)",
            budget.floorSumDp <= 900f
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1112dp")
    fun `Fold 8 unfolded (840dp) stays on the compact budget`() {
        val budget = budget()
        assertEquals(
            "An unfolded Fold 8 (exactly 840dp wide) must resolve the COMPACT rail. If this " +
                "fails, the tablet band boundary moved back from w900dp to w840dp and the " +
                "conversation pane on that 7.6\" handheld drops from 487dp to 383dp — the " +
                "cramped-split complaint the 900dp boundary exists to fix.",
            72f,
            budget.railDp,
            TOLERANCE_DP
        )
        assertEquals("list default at 840dp", 280f, budget.listDefaultDp, TOLERANCE_DP)
    }

    @Test
    @Config(qualifiers = "w899dp-h480dp")
    fun `top of the compact band (899dp) still resolves the compact budget`() {
        assertEquals(72f, budget().railDp, TOLERANCE_DP)
    }

    @Test
    fun `the drag floor is 360dp and never below the static floor, in both bands`() {
        val compact = budget()
        assertEquals(
            "dual_pane_detail_drag_min_width (res/values/dimens.xml) is the interactive clamp " +
                "that keeps incoming rows hosting the 270dp fixed-width bubbles (26 + 270 + 40 " +
                "= 336dp, 340dp with the voice-speed button) out of the clipping zone.",
            360f,
            compact.detailDragMinDp,
            TOLERANCE_DP
        )
        assertTrue(
            "drag floor must never sit below the static floor",
            compact.detailDragMinDp >= compact.detailMinDp
        )

        val tablet = Budget(
            RuntimeEnvironment.getApplication().createConfigurationContext(
                Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply {
                    screenWidthDp = 900
                    screenHeightDp = 480
                }
            ).resources
        )
        assertEquals(
            "the drag floor is deliberately band-independent — values-w900dp/ must NOT override it",
            360f,
            tablet.detailDragMinDp,
            TOLERANCE_DP
        )
        assertTrue(
            "drag floor must never sit below the tablet band's static floor",
            tablet.detailDragMinDp >= tablet.detailMinDp
        )
    }

    @Test
    fun `the dual-pane gate bool flips exactly at the declared floor constants`() {
        val floorWidthDp = WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP
        val floorHeightDp = WindowSizeClassUtil.MIN_HEIGHT_FOR_DUAL_PANE_DP
        val renameHint =
            "app/src/main/res/values-w${floorWidthDp}dp-h${floorHeightDp}dp/bools.xml (and " +
                "app/src/main/res/layout-w${floorWidthDp}dp-h${floorHeightDp}dp/ with it) must " +
                "carry the floor this test reads from WindowSizeClassUtil — the sum pinned by " +
                "the first case in this file."

        assertTrue(
            "R.bool.dual_pane_layout_active MUST be true at the declared floor " +
                "(${floorWidthDp}dp x ${floorHeightDp}dp). $renameHint",
            gateAt(floorWidthDp, floorHeightDp),
        )
        assertFalse(
            "R.bool.dual_pane_layout_active MUST be false one dp below the declared width floor " +
                "(${floorWidthDp - 1}dp), where rail + divider + list-min + detail-min no longer " +
                "fit. $renameHint",
            gateAt(floorWidthDp - 1, floorHeightDp),
        )
        assertFalse(
            "R.bool.dual_pane_layout_active MUST be false one dp below the declared height floor " +
                "(${floorHeightDp - 1}dp) — the folded-landscape guard. $renameHint",
            gateAt(floorWidthDp, floorHeightDp - 1),
        )
    }

    /**
     * Resolves the gate bool in a configuration built from the declared floor constants. A
     * `@Config(qualifiers = ...)` annotation could not do this: its value has to be a literal,
     * and a literal cannot fail when a constant moves.
     */
    private fun gateAt(screenWidthDp: Int, screenHeightDp: Int): Boolean {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            this.screenWidthDp = screenWidthDp
            this.screenHeightDp = screenHeightDp
        }
        return application.createConfigurationContext(configuration)
            .resources
            .getBoolean(R.bool.dual_pane_layout_active)
    }

    private companion object {
        /** dp comparison tolerance: dimens round-trip through px, so exact float equality is unsafe. */
        const val TOLERANCE_DP = 0.01f
    }
}
