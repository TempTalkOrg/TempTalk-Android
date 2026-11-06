package com.difft.android.dualpane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StaleLayoutDecision] on the #1454 field values (Xiaomi 18 Fold, both panels at 2.75): outer
 * panel 1168x1712 px = 424x622 dp, inner configuration 860x608 dp.
 */
class StaleLayoutDecisionTest {

    private fun px(dp: Int, density: Float = DENSITY) = (dp * density).toInt()

    private fun decide(
        tree: Boolean = true,
        cfgSwDp: Int,
        cfgLongDp: Int,
        cfgDensity: Float = DENSITY,
        panelShortPx: Int = OUTER_SHORT_PX,
        panelLongPx: Int = OUTER_LONG_PX,
        panelShortDp: Int = 424,
        panelLongDp: Int = 622,
    ) = StaleLayoutDecision.decide(
        treeIsDualPane = tree,
        cfgSwPx = px(cfgSwDp, cfgDensity),
        cfgLongPx = px(cfgLongDp, cfgDensity),
        panelShortPx = panelShortPx,
        panelLongPx = panelLongPx,
        panelShortDp = panelShortDp,
        panelLongDp = panelLongDp,
        marginPx = px(32),
    )

    @Test
    fun `incident - inner configuration with a dual-pane tree on the outer panel is stale`() {
        assertTrue(decide(cfgSwDp = 608, cfgLongDp = 860))
    }

    @Test
    fun `healthy outer panel configuration is not stale`() {
        assertFalse(decide(cfgSwDp = 425, cfgLongDp = 622))
    }

    @Test
    fun `a dp of rounding is not stale`() {
        assertFalse(decide(cfgSwDp = 425, cfgLongDp = 623))
    }

    @Test
    fun `inner panel with its own configuration is not stale`() {
        assertFalse(decide(cfgSwDp = 608, cfgLongDp = 859, panelShortPx = 1672, panelLongPx = 2364, panelShortDp = 608, panelLongDp = 859))
    }

    @Test
    fun `an unknown panel is never stale`() {
        assertFalse(decide(cfgSwDp = 608, cfgLongDp = 860, panelShortPx = 0, panelLongPx = 0, panelShortDp = 0, panelLongDp = 0))
    }

    @Test
    fun `a one-pane tree is never stale`() {
        assertFalse(decide(tree = false, cfgSwDp = 608, cfgLongDp = 860))
    }

    @Test
    fun `the unfold direction - a small configuration on the large panel - is not stale`() {
        assertFalse(decide(cfgSwDp = 424, cfgLongDp = 622, panelShortPx = 1672, panelLongPx = 2364, panelShortDp = 608, panelLongDp = 859))
    }

    @Test
    fun `a display size change keeps px, so dp and density moving together are not stale`() {
        // Larger display size: 2.75 -> 3.0, the configuration's dp shrink with it.
        assertFalse(decide(cfgSwDp = 389, cfgLongDp = 570, cfgDensity = 3.0f))
    }

    @Test
    fun `boundary panel 480x672 dp cannot host dual pane, so only the size test decides`() {
        val shortPx = px(480)
        val longPx = px(672)
        assertTrue(
            "configuration larger than the panel",
            decide(cfgSwDp = 608, cfgLongDp = 860, panelShortPx = shortPx, panelLongPx = longPx, panelShortDp = 480, panelLongDp = 672),
        )
        assertFalse(
            "configuration fits the panel",
            decide(cfgSwDp = 480, cfgLongDp = 672, panelShortPx = shortPx, panelLongPx = longPx, panelShortDp = 480, panelLongDp = 672),
        )
    }

    @Test
    fun `a panel that can host dual pane in landscape is never stale`() {
        assertFalse(
            decide(cfgSwDp = 900, cfgLongDp = 1200, panelShortPx = px(480), panelLongPx = px(673), panelShortDp = 480, panelLongDp = 673),
        )
    }

    private companion object {
        const val DENSITY = 2.75f
        const val OUTER_SHORT_PX = 1168
        const val OUTER_LONG_PX = 1712
    }
}
