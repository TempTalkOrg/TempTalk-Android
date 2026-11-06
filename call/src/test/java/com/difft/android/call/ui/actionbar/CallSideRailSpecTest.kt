package com.difft.android.call.ui.actionbar

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.difft.android.call.LCallUiConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The side-rail rule (#1451): landscape and at least 480dp tall, nothing else. */
class CallSideRailSpecTest {

    @Test
    fun `rail only on landscape windows at least 480dp tall`() {
        assertTrue("fold inner landscape", CallSideRailSpec.showsRail(860f, 608f))
        assertFalse("fold inner portrait", CallSideRailSpec.showsRail(608f, 860f))
        assertFalse("phone landscape (screen share)", CallSideRailSpec.showsRail(852f, 393f))
        assertTrue("near-square landscape", CallSideRailSpec.showsRail(900f, 810f))
        assertTrue("16:10 tablet landscape", CallSideRailSpec.showsRail(1280f, 800f))
        assertFalse("square is not landscape", CallSideRailSpec.showsRail(800f, 800f))
        assertTrue("height exactly at the threshold", CallSideRailSpec.showsRail(700f, 480f))
        assertFalse("just below the threshold", CallSideRailSpec.showsRail(700f, 479.9f))
    }

    @Test
    fun `minimize plus six controls fit at the minimum height under a status bar up to 52dp`() {
        assertEquals(348, CallSideRailSpec.STACK_HEIGHT_DP)
        // 48 minimize + 8 gap + 348 stack + 24 bottom inset.
        assertEquals(428, CallSideRailSpec.HEIGHT_BELOW_TOP_INSET_DP)
        val maxStatusBar = CallSideRailSpec.MIN_WINDOW_HEIGHT_DP - CallSideRailSpec.HEIGHT_BELOW_TOP_INSET_DP
        assertEquals(52, maxStatusBar)
        // The stack's top also clears the title bar (status bar + 56) by the same margin.
        val stackTop = CallSideRailSpec.MIN_WINDOW_HEIGHT_DP - CallSideRailSpec.BOTTOM_INSET_DP - CallSideRailSpec.STACK_HEIGHT_DP
        assertTrue(stackTop - maxStatusBar >= LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP)
    }

    @Test
    fun `insets default to the prototype`() {
        val plain = resolveSideRail(860f, 608f)!!
        assertEquals(84, plain.widthDp)
        assertEquals(24, plain.endInsetDp)
        assertEquals(24, plain.bottomInsetDp)
        assertEquals(LCallUiConstants.TOP_BAR_MARGIN_TOP_DP.dp, plain.topInset)
        assertTrue(plain.fitsSevenControls)
    }

    @Test
    fun `top clears the status band`() {
        assertEquals(32.dp, resolveSideRail(860f, 608f, statusBandDp = 32f)!!.topInset)
    }

    @Test
    fun `a cutout over the rail band pushes only the top down, never the column`() {
        // Fold inner landscape: the camera cutout is a 51x60dp rect in the top-end corner.
        val rail = resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = listOf(foldCamera))!!
        assertEquals(60.dp, rail.topInset)
        assertEquals(84, rail.widthDp)
        assertEquals(24, rail.bottomInsetDp)
        // A status band taller than the cutout wins.
        assertEquals(70.dp, resolveSideRail(860f, 608f, statusBandDp = 70f, cutouts = listOf(foldCamera))!!.topInset)
    }

    @Test
    fun `a cutout outside the rail band changes nothing`() {
        val elsewhere = listOf(
            Rect(0f, 0f, 51f, 60f), // top-start corner
            Rect(0f, 548f, 51f, 608f), // bottom-start corner
            Rect(400f, 0f, 460f, 30f), // top centre
            Rect(700f, 0f, 776f, 60f), // ends exactly at the band's start edge
        )
        assertEquals(resolveSideRail(860f, 608f, statusBandDp = 32f), resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = elsewhere))
    }

    @Test
    fun `a cutout at the bottom of the band raises the bottom inset only`() {
        val rail = resolveSideRail(860f, 608f, cutouts = listOf(Rect(820f, 578f, 860f, 608f)))!!
        assertEquals(30, rail.bottomInsetDp)
        assertEquals(84, rail.widthDp)
        assertEquals(0.dp, rail.topInset)
    }

    @Test
    fun `RTL checks the band on the left edge`() {
        assertEquals(60.dp, resolveSideRail(860f, 608f, cutouts = listOf(Rect(0f, 0f, 51f, 60f)), isRtl = true)!!.topInset)
        assertEquals(0.dp, resolveSideRail(860f, 608f, cutouts = listOf(foldCamera), isRtl = true)!!.topInset)
    }

    @Test
    fun `cutout clearance is vertical only and ignores empty or off-window rects`() {
        val clearance = railCutoutClearance(
            cutouts = listOf(foldCamera, Rect(830f, 0f, 830f, 90f), Rect(830f, -40f, 860f, 0f)),
            windowWidthDp = 860f,
            windowHeightDp = 608f,
            railWidthDp = 84f,
            isRtl = false,
        )
        assertEquals(RailCutoutClearance(topDp = 60f, bottomDp = 0f), clearance)
    }

    @Test
    fun `at the minimum height the controls win over a tall top band`() {
        val rail = resolveSideRail(700f, 480f, cutouts = listOf(Rect(650f, 0f, 700f, 80f)))!!
        assertEquals((480 - CallSideRailSpec.HEIGHT_BELOW_TOP_INSET_DP).dp, rail.topInset)
        assertFalse(rail.fitsSevenControls)
    }

    @Test
    fun `seven controls need minimize, gap, the taller stack and the bottom inset below the top`() {
        assertEquals(408, CallSideRailSpec.ROOMY_STACK_HEIGHT_DP)
        // 48 + 8 + 408 + 24 = 488 below the top inset.
        assertTrue(resolveSideRail(1000f, 540f, statusBandDp = 52f)!!.fitsSevenControls)
        assertFalse(resolveSideRail(1000f, 540f, statusBandDp = 53f)!!.fitsSevenControls)
        // The fold's inner screen fits them under its camera cutout.
        assertTrue(resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = listOf(foldCamera))!!.fitsSevenControls)
    }

    @Test
    fun `no rail off the rule even with a cutout`() {
        assertNull(resolveSideRail(608f, 860f, cutouts = listOf(foldCamera)))
        assertNull(resolveSideRail(852f, 393f))
    }

    private val foldCamera = Rect(809f, 0f, 860f, 60f)
}
