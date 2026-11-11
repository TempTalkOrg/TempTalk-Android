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








@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class DualPaneBudgetResourceInvariantTest {

    private class Budget(resources: Resources) {
        private val density = resources.displayMetrics.density
        val dividerDp = resources.getDimension(R.dimen.dual_pane_divider_width) / density

        
        val hairlineDp = 1f / density
        val listDefaultDp = resources.getDimension(R.dimen.dual_pane_list_default_width) / density
        val listMinDp = resources.getDimension(R.dimen.dual_pane_list_min_width) / density
        val detailMinDp = resources.getDimension(R.dimen.dual_pane_detail_min_width) / density
        val detailDragMinDp =
            resources.getDimension(R.dimen.dual_pane_detail_drag_min_width) / density
        val band: String = resources.getString(R.string.dual_pane_band)
        val floorSumDp = listMinDp + detailMinDp
    }

    private fun budget() = Budget(RuntimeEnvironment.getApplication().resources)

    @Test
    fun `default config carries the compact budget and its floor fits under the qualifier gate`() {
        val budget = budget()

        assertEquals("divider: the shared hairline", budget.hairlineDp, budget.dividerDp, TOLERANCE_DP)
        assertEquals("list default (res/values/dimens.xml)", 280f, budget.listDefaultDp, TOLERANCE_DP)
        assertEquals("list minimum (res/values/dimens.xml)", 280f, budget.listMinDp, TOLERANCE_DP)
        assertEquals("detail minimum (res/values/dimens.xml)", 320f, budget.detailMinDp, TOLERANCE_DP)
        assertEquals("band marker (res/values/dual_pane_band.xml)", "compact", budget.band)

        assertTrue(
            "list-min + detail-min (${budget.floorSumDp}dp) must not exceed " +
                "WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP, which is the w673dp component of " +
                "app/src/main/res/layout-w673dp-h480dp/. Raising a minimum in " +
                "res/values/dimens.xml above the gate requires renaming that directory and " +
                "updating the constant.",
            budget.floorSumDp <= WindowSizeClassUtil.DUAL_PANE_MIN_WIDTH_DP.toFloat()
        )
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp")
    fun `w900dp resolves the tablet budget`() {
        val budget = budget()

        
        assertEquals("divider: the shared hairline in every band", budget.hairlineDp, budget.dividerDp, TOLERANCE_DP)
        assertEquals("list default (res/values-w900dp/dimens.xml)", 360f, budget.listDefaultDp, TOLERANCE_DP)
        assertEquals("list minimum (res/values-w900dp/dimens.xml)", 280f, budget.listMinDp, TOLERANCE_DP)
        assertEquals("detail minimum (res/values-w900dp/dimens.xml)", 360f, budget.detailMinDp, TOLERANCE_DP)
        assertEquals("band marker (res/values-w900dp/dual_pane_band.xml)", "tablet", budget.band)

        assertEquals("tablet-band feasibility floor", 640f, budget.floorSumDp, TOLERANCE_DP)
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
            "An unfolded Fold 8 (exactly 840dp wide) must resolve the COMPACT list default. If " +
                "this fails, the tablet band boundary moved back from w900dp to w840dp and the " +
                "conversation pane on that 7.6\" handheld loses 80dp — the cramped-split " +
                "complaint the 900dp boundary exists to fix.",
            280f,
            budget.listDefaultDp,
            TOLERANCE_DP
        )
        assertEquals("band marker at 840dp", "compact", budget.band)
    }

    @Test
    @Config(qualifiers = "w899dp-h480dp")
    fun `top of the compact band (899dp) still resolves the compact budget`() {
        assertEquals(280f, budget().listDefaultDp, TOLERANCE_DP)
        assertEquals("compact", budget().band)
    }

    @Test
    fun `the drag floor is 360dp and never below the static floor, in both bands`() {
        val compact = budget()
        assertEquals(
            "dual_pane_detail_drag_min_width (res/values/dimens.xml) is the interactive clamp " +
                "that keeps incoming rows hosting the 280dp fixed-width cards (26 + 280 + 40 " +
                "= 346dp) out of the clipping zone.",
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
                "(${floorWidthDp - 1}dp), below the declared gate. $renameHint",
            gateAt(floorWidthDp - 1, floorHeightDp),
        )
        assertFalse(
            "R.bool.dual_pane_layout_active MUST be false one dp below the declared height floor " +
                "(${floorHeightDp - 1}dp) — the folded-landscape guard. $renameHint",
            gateAt(floorWidthDp, floorHeightDp - 1),
        )
    }

    




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
        
        const val TOLERANCE_DP = 0.01f
    }
}
