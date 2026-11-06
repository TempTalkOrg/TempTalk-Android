package com.difft.android.call.ui.actionbar

import androidx.compose.ui.geometry.Rect
import com.difft.android.call.ui.screenshare.speakerBannerTopDp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The title row and the speaker banner clear the display cutout where it meets them (#1451). */
class TitleRowCutoutTest {

    @Test
    fun `no cutout keeps the status-bar rule`() {
        assertEquals(0f, titleRowTopDp(emptyList(), 608f, 860f, baseTopDp = 0f), 0f)
        assertEquals(32f, titleRowTopDp(emptyList(), 608f, 860f, baseTopDp = 32f), 0f)
    }

    @Test
    fun `fold portrait share pushes the row below the top-start camera`() {
        // Xiaomi 18 Fold inner screen, status bar hidden by the share: (0,0)-(164,140)px at 2.75.
        assertEquals(foldPortraitCamera.bottom, titleRowTopDp(listOf(foldPortraitCamera), 608f, 860f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a status bar taller than the cutout wins and a shorter one is extended`() {
        assertEquals(56f, titleRowTopDp(listOf(foldPortraitCamera), 608f, 860f, baseTopDp = 56f), 0f)
        assertEquals(foldPortraitCamera.bottom, titleRowTopDp(listOf(foldPortraitCamera), 608f, 860f, baseTopDp = 40f), 0f)
    }

    @Test
    fun `a centred punch hole in portrait share pushes the row`() {
        assertEquals(40f, titleRowTopDp(listOf(Rect(186f, 0f, 226f, 40f)), 412f, 915f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a side punch hole mid-height in landscape share changes nothing`() {
        // Pixel 8 Pro landscape: the portrait top-centre hole sits on the left edge, mid-height.
        val side = listOf(Rect(0f, 180f, 40f, 220f))
        assertEquals(0f, titleRowTopDp(side, 915f, 412f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a side punch hole inside the row band pushes the row below it`() {
        // Minimize sits at the start of the row, right under such a hole.
        assertEquals(50f, titleRowTopDp(listOf(Rect(0f, 10f, 40f, 50f)), 915f, 412f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `rects below the band, in the lower half, empty or off-window change nothing`() {
        val elsewhere = listOf(
            Rect(0f, 52f, 60f, 100f), // starts where the row ends
            Rect(0f, 700f, 60f, 760f), // lower half
            Rect(100f, 0f, 100f, 40f), // empty
            Rect(-60f, 0f, 0f, 40f), // left of the window
            Rect(608f, 0f, 660f, 40f), // right of the window
        )
        assertEquals(0f, titleRowTopDp(elsewhere, 608f, 860f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a band pushed below one rect still clears the next`() {
        val chained = listOf(Rect(300f, 45f, 340f, 70f), Rect(0f, 0f, 60f, 40f))
        assertEquals(70f, titleRowTopDp(chained, 608f, 860f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `with the rail the row stays level with the rail minimize outside a share`() {
        // Fold inner landscape: the camera is in the rail's band at the top-end corner.
        val rail = resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = listOf(foldLandscapeCamera))!!
        assertEquals(60f, rail.topInset.value, 0f)
        assertEquals(60f, titleRowTopDp(listOf(foldLandscapeCamera), 860f, 608f, 32f, rail, alignWithRail = true), 0f)
    }

    @Test
    fun `with the rail a share leaves the row at the top when only the rail band meets the cutout`() {
        // The centred title is all the row holds; the rail clears its own band.
        val rail = resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = listOf(foldLandscapeCamera))!!
        assertEquals(0f, titleRowTopDp(listOf(foldLandscapeCamera), 860f, 608f, 0f, rail, alignWithRail = false), 0f)
    }

    @Test
    fun `with the rail a start-corner cutout leaves the empty minimize slot and the alignment alone`() {
        val startCorner = listOf(Rect(0f, 0f, 51f, 60f))
        val rail = resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = startCorner)!!
        assertEquals(32f, titleRowTopDp(startCorner, 860f, 608f, 32f, rail, alignWithRail = true), 0f)
    }

    @Test
    fun `the speaker banner rests below a cutout in its column only`() {
        assertEquals(17f, speakerBannerTopDp(emptyList(), 608f, 860f, endGapDp = 0f, isRtl = false), 0f)
        // Banner column without a rail on a 608dp window: 433..593.
        assertEquals(40f, speakerBannerTopDp(listOf(Rect(500f, 0f, 540f, 40f)), 608f, 860f, 0f, isRtl = false), 0f)
        // The fold's top-start camera is nowhere near the end-side banner.
        assertEquals(17f, speakerBannerTopDp(listOf(foldPortraitCamera), 608f, 860f, 0f, isRtl = false), 0f)
        // RTL mirrors the column to the left edge.
        assertEquals(foldPortraitCamera.bottom, speakerBannerTopDp(listOf(foldPortraitCamera), 608f, 860f, 0f, isRtl = true), 0f)
    }

    @Test
    fun `beside the rail the banner ignores a cutout in the rail band`() {
        assertEquals(17f, speakerBannerTopDp(listOf(foldLandscapeCamera), 860f, 608f, endGapDp = 84f, isRtl = false), 0f)
    }

    private val foldPortraitCamera = Rect(0f, 0f, 164f / 2.75f, 140f / 2.75f)
    private val foldLandscapeCamera = Rect(809f, 0f, 860f, 60f)
}
