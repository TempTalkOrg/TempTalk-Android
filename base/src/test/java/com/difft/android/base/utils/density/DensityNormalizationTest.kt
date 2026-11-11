package com.difft.android.base.utils.density

import com.difft.android.base.utils.density.DensityNormalization.Inputs
import com.difft.android.base.utils.density.DensityNormalization.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class DensityNormalizationTest {

    private fun decide(inputs: Inputs, scale: Float = 1f) = DensityNormalization.decide(inputs, scale)

    private fun panel(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        xdpi: Float,
        ydpi: Float = xdpi,
        stable: Int = densityDpi,
        maxModeLongPx: Int = maxOf(widthPx, heightPx),
    ) = Inputs(densityDpi, stable, xdpi, ydpi, widthPx, heightPx, maxModeLongPx)

    private val xiaomiOuter = panel(1168, 1712, 440, 385.288f, 385.162f)
    private val xiaomiInner = panel(1672, 2364, 440, 381.913f, 381.969f)
    private val vivo = panel(1120, 2528, 520, 424.748f, 424.750f)
    private val flipMain = panel(1080, 2640, 480, 428.625f, 424.405f)

    @Test
    fun `biased panels shrink to the physical size, never past the floor`() {
        assertEquals(Decision(387, 0.88f), decide(xiaomiOuter).summary())
        assertEquals(Decision(383, 0.87f), decide(xiaomiInner).summary())
        assertEquals(Decision(442, 0.85f), decide(vivo).summary())
        assertEquals(Decision(427, 0.89f), decide(flipMain).summary())
    }

    
    
    private val pixelFhd = panel(1008, 2244, 360, 365.76f, 367.726f, stable = 480, maxModeLongPx = 2992)

    @Test
    fun `a panel already at its physical size is left alone`() {
        val decision = decide(pixelFhd)
        assertEquals(Reason.NEUTRAL, decision.reason)
        assertEquals(360, decision.densityDpi)
        assertTrue("the factory default is scaled to the FHD mode", decision.baselineIsStable)
    }

    @Test
    fun `a larger system Display size is ignored`() {
        
        for (dpi in listOf(520, 598, 676)) assertEquals("dpi=$dpi", 442, decide(vivo.copy(densityDpi = dpi)).densityDpi)
        assertEquals(360, decide(pixelFhd.copy(densityDpi = 414)).densityDpi)
    }

    @Test
    fun `the display size scales the standard density`() {
        assertEquals(398, decide(vivo, DisplaySize.SMALL.scale).densityDpi)
        assertEquals(508, decide(vivo, DisplaySize.LARGE.scale).densityDpi)
        assertEquals(348, decide(xiaomiOuter, DisplaySize.SMALL.scale).densityDpi)
        assertEquals(445, decide(xiaomiOuter, DisplaySize.LARGE.scale).densityDpi)
        
        assertEquals(414, decide(pixelFhd, DisplaySize.LARGE.scale).densityDpi)
        assertEquals(324, decide(pixelFhd, DisplaySize.SMALL.scale).densityDpi)
    }

    @Test
    fun `enlarging keeps the short side at 320dp`() {
        
        val dense = panel(1080, 2400, 520, 515f)
        assertEquals(Reason.NEUTRAL, decide(dense).reason)
        assertEquals(540, decide(dense, DisplaySize.LARGE.scale).densityDpi)
    }

    @Test
    fun `a large panel enlarges only as far as the dual-pane decision holds`() {
        
        val inner = panel(1840, 2208, 380, 381f)
        assertEquals(Reason.NEUTRAL, decide(inner).reason)
        
        val large = decide(inner, DisplaySize.LARGE.scale).densityDpi
        assertEquals(408, large)
        assertTrue(1840 * 160 / large >= 673 + 48)
    }

    @Test
    fun `a window that passes the dual-pane gate still passes it`() {
        
        val inner = panel(1840, 2208, 380, 381f).copy(windowWidthDp = 700, windowHeightDp = 800)
        assertEquals(395, decide(inner, DisplaySize.LARGE.scale).densityDpi)
    }

    @Test
    fun `a phone whose landscape reaches the gate does not enlarge past it`() {
        
        assertEquals(306, decide(pixelFhd.copy(densityDpi = 306)).densityDpi)
        assertEquals(360, decide(pixelFhd.copy(densityDpi = 306)).standardDpi)
    }

    @Test
    fun `a forced logical resolution bounds the enlargement by the logical size`() {
        
        val forced = pixelFhd.copy(realWidthPx = 800, realHeightPx = 1780)
        assertEquals(400, DensityNormalization.enlargementCap(forced))
    }

    @Test
    fun `an untrusted panel still ignores the system Display size`() {
        
        val decision = decide(vivo.copy(densityDpi = 676, xdpi = 0f, ydpi = 0f), DisplaySize.SMALL.scale)
        assertEquals(Reason.PLACEHOLDER, decision.reason)
        assertEquals(520, decision.standardDpi)
        assertEquals(468, decision.densityDpi)
    }

    @Test
    fun `the first size follows the system Display size`() {
        assertEquals(1f, decide(vivo).systemRatio)
        assertEquals(DisplaySize.LARGE, DisplaySize.closestTo(decide(vivo.copy(densityDpi = 598)).systemRatio))
        assertEquals(DisplaySize.SMALL, DisplaySize.closestTo(decide(vivo.copy(densityDpi = 442)).systemRatio))
        assertEquals(DisplaySize.LARGE, DisplaySize.closestTo(decide(vivo.copy(densityDpi = 676)).systemRatio))
        assertEquals(DisplaySize.STANDARD, DisplaySize.closestTo(1.06f))
        assertEquals(DisplaySize.LARGE, DisplaySize.closestTo(1.08f))
        
        assertEquals(1f, decide(xiaomiOuter.copy(stableDensityDpi = 160)).systemRatio)
    }

    @Test
    fun `an unknown factory density falls back to the current one as the baseline`() {
        
        val decision = decide(xiaomiOuter.copy(stableDensityDpi = 160))
        assertEquals(Reason.APPLIED, decision.reason)
        assertFalse(decision.baselineIsStable)
        assertEquals(387, decision.densityDpi)
    }

    @Test
    fun `placeholder dpi values keep the system density`() {
        for (placeholder in listOf(0f, 160f, 213f, 320f, Float.NaN, -1f)) {
            val decision = decide(xiaomiOuter.copy(xdpi = placeholder, ydpi = placeholder))
            assertEquals("xdpi=$placeholder", Reason.PLACEHOLDER, decision.reason)
            assertEquals(440, decision.densityDpi)
        }
        
        assertEquals(Reason.PLACEHOLDER, decide(xiaomiOuter.copy(xdpi = 440f, ydpi = 440f)).reason)
    }

    @Test
    fun `non-square pixels keep the system density`() {
        assertEquals(Reason.NON_SQUARE, decide(xiaomiOuter.copy(xdpi = 385f, ydpi = 420f)).reason)
    }

    @Test
    fun `another display's dpi is rejected by the panel size`() {
        
        val decision = decide(flipMain.copy(xdpi = 306.438f, ydpi = 306.438f))
        assertEquals(Reason.IMPLAUSIBLE_SIZE, decision.reason)
        assertEquals(480, decision.densityDpi)
    }

    @Test
    fun `a bias outside the plausible range keeps the system density`() {
        
        assertEquals(Reason.IMPLAUSIBLE_BIAS, decide(panel(1080, 2400, 640, 400f)).reason)
        
        assertEquals(Reason.IMPLAUSIBLE_BIAS, decide(panel(1080, 2400, 400, 445f)).reason)
    }

    @Test
    fun `a forced logical resolution keeps the system density`() {
        
        
        val decision = decide(xiaomiOuter.copy(densityDpi = 330))
        assertEquals(Reason.IMPLAUSIBLE_BIAS, decision.reason)
        assertEquals(330, decision.densityDpi)
    }

    private data class Decision(val densityDpi: Int, val factor: Float)

    private fun DensityNormalization.Decision.summary(): Decision {
        assertEquals(Reason.APPLIED, reason)
        return Decision(densityDpi, factor)
    }
}
