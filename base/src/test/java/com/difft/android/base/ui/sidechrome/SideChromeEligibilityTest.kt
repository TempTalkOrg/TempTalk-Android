package com.difft.android.base.ui.sidechrome

import com.difft.android.base.ui.sidechrome.SideChromeEligibility.Inputs
import com.difft.android.base.ui.sidechrome.SideChromeEligibility.Reason
import org.junit.Assert.assertEquals
import org.junit.Test


class SideChromeEligibilityTest {

    private fun foldable(widthDp: Int, heightDp: Int, contentDp: Float, dualPaneMin: Float = 0f) = Inputs(
        hasHinge = true,
        onDefaultDisplay = true,
        inMultiWindow = false,
        screenWidthDp = widthDp,
        screenHeightDp = heightDp,
        contentWidthDp = contentDp,
        dualPaneMinWidthDp = dualPaneMin,
    )

    
    private val xiaomiOuter = foldable(424, 622, 483f)
    private val xiaomiInnerLandscape = foldable(859, 608, 987f, dualPaneMin = 600f)

    @Test
    fun `short foldable windows are eligible`() {
        assertEquals(Reason.ELIGIBLE, SideChromeEligibility.evaluate(xiaomiOuter))
        assertEquals(Reason.ELIGIBLE, SideChromeEligibility.evaluate(xiaomiInnerLandscape))
    }

    @Test
    fun `tall and narrow windows are not`() {
        
        assertEquals(Reason.TALL, SideChromeEligibility.evaluate(foldable(608, 859, 698f)))
        
        assertEquals(Reason.TALL, SideChromeEligibility.evaluate(foldable(360, 880, 405f)))
        assertEquals(Reason.NARROW, SideChromeEligibility.evaluate(foldable(880, 360, 990f)))
    }

    @Test
    fun `phones, tablets, other displays and split screen are not`() {
        assertEquals(Reason.NO_HINGE, SideChromeEligibility.evaluate(xiaomiOuter.copy(hasHinge = false)))
        assertEquals(Reason.EXTERNAL_DISPLAY, SideChromeEligibility.evaluate(xiaomiOuter.copy(onDefaultDisplay = false)))
        assertEquals(Reason.MULTI_WINDOW, SideChromeEligibility.evaluate(xiaomiOuter.copy(inMultiWindow = true)))
    }

    @Test
    fun `the bar must leave 320dp for the page`() {
        
        assertEquals(Reason.CONTENT, SideChromeEligibility.evaluate(xiaomiOuter.copy(contentWidthDp = 403f)))
        assertEquals(Reason.ELIGIBLE, SideChromeEligibility.evaluate(xiaomiOuter.copy(contentWidthDp = 404f)))
    }

    @Test
    fun `two panes must still fit beside the bar`() {
        
        
        assertEquals(Reason.DUAL_PANE, SideChromeEligibility.evaluate(xiaomiInnerLandscape.copy(contentWidthDp = 652f)))
        assertEquals(Reason.ELIGIBLE, SideChromeEligibility.evaluate(xiaomiInnerLandscape.copy(contentWidthDp = 685f)))
    }

    @Test
    fun `the placement setting is offered on short screens in either orientation`() {
        fun short(widthDp: Int, heightDp: Int, hinge: Boolean = true) =
            SideChromeEligibility.shortScreen(hasHinge = hinge, onDefaultDisplay = true, screenWidthDp = widthDp, screenHeightDp = heightDp)

        
        assertEquals(true, short(424, 622))
        assertEquals(true, short(608, 859))
        assertEquals(true, short(859, 608))
        
        assertEquals(false, short(711, 770))
        
        assertEquals(false, short(360, 780))
        assertEquals(false, short(424, 622, hinge = false))
    }
}
