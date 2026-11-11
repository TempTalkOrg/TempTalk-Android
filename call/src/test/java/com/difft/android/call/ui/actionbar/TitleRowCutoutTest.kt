package com.difft.android.call.ui.actionbar

import androidx.compose.ui.geometry.Rect
import com.difft.android.call.ui.screenshare.speakerBannerTopDp
import org.junit.Assert.assertEquals
import org.junit.Test


class TitleRowCutoutTest {

    @Test
    fun `no cutout keeps the status-bar rule`() {
        assertEquals(0f, titleRowTopDp(emptyList(), 608f, 860f, baseTopDp = 0f), 0f)
        assertEquals(32f, titleRowTopDp(emptyList(), 608f, 860f, baseTopDp = 32f), 0f)
    }

    @Test
    fun `fold portrait share keeps the row up and moves minimize past the top-start camera`() {
        
        val camera = listOf(foldPortraitCamera)
        assertEquals(0f, titleRowTopDp(camera, 608f, 860f, baseTopDp = 0f, minimizeAside = true), 0f)
        assertEquals(foldPortraitCamera.right, minimizeStartDp(camera, 608f, rowTopDp = 0f, basePaddingDp = 16f, isRtl = false), 0f)
    }

    @Test
    fun `a status bar taller than the cutout wins and a shorter one is extended`() {
        val hole = listOf(Rect(186f, 0f, 226f, 48f))
        assertEquals(56f, titleRowTopDp(hole, 412f, 915f, baseTopDp = 56f), 0f)
        assertEquals(48f, titleRowTopDp(hole, 412f, 915f, baseTopDp = 40f), 0f)
    }

    @Test
    fun `a centred punch hole in portrait share pushes the row`() {
        assertEquals(40f, titleRowTopDp(listOf(Rect(186f, 0f, 226f, 40f)), 412f, 915f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a side punch hole mid-height in landscape share changes nothing`() {
        
        val side = listOf(Rect(0f, 180f, 40f, 220f))
        assertEquals(0f, titleRowTopDp(side, 915f, 412f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a side punch hole inside the row band moves minimize, not the row`() {
        
        val side = listOf(Rect(0f, 17f, 36f, 41f))
        assertEquals(0f, titleRowTopDp(side, 770f, 711f, baseTopDp = 0f, minimizeAside = true), 0f)
        assertEquals(36f, minimizeStartDp(side, 770f, rowTopDp = 0f, basePaddingDp = 18f, isRtl = false), 0f)
    }

    @Test
    fun `under the shown status bar the whole row still clears the cutout`() {
        
        val side = listOf(Rect(0f, 17f, 36f, 41f))
        assertEquals(41f, titleRowTopDp(side, 770f, 711f, baseTopDp = 38f), 0f)
        assertEquals(18f, minimizeStartDp(side, 770f, rowTopDp = 41f, basePaddingDp = 18f, isRtl = false), 0f)
        assertEquals(50f, titleRowTopDp(listOf(Rect(0f, 10f, 40f, 50f)), 915f, 412f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `over the picture a centred punch hole still pushes the row`() {
        assertEquals(40f, titleRowTopDp(listOf(Rect(186f, 0f, 226f, 40f)), 412f, 915f, baseTopDp = 0f, minimizeAside = true), 0f)
    }

    @Test
    fun `minimize keeps its padding when the cutout misses its slot or its row`() {
        val side = listOf(Rect(0f, 17f, 36f, 41f))
        
        assertEquals(18f, minimizeStartDp(side, 770f, rowTopDp = 41f, basePaddingDp = 18f, isRtl = false), 0f)
        
        assertEquals(18f, minimizeStartDp(side, 770f, rowTopDp = 0f, basePaddingDp = 18f, isRtl = true), 0f)
        assertEquals(36f, minimizeStartDp(listOf(Rect(734f, 17f, 770f, 41f)), 770f, 0f, 18f, isRtl = true), 0f)
    }

    @Test
    fun `rects below the band, in the lower half, empty or off-window change nothing`() {
        val elsewhere = listOf(
            Rect(0f, 52f, 60f, 100f), 
            Rect(0f, 700f, 60f, 760f), 
            Rect(100f, 0f, 100f, 40f), 
            Rect(-60f, 0f, 0f, 40f), 
            Rect(608f, 0f, 660f, 40f), 
        )
        assertEquals(0f, titleRowTopDp(elsewhere, 608f, 860f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `a band pushed below one rect still clears the next`() {
        val chained = listOf(Rect(300f, 45f, 340f, 70f), Rect(200f, 0f, 260f, 40f))
        assertEquals(70f, titleRowTopDp(chained, 608f, 860f, baseTopDp = 0f), 0f)
    }

    @Test
    fun `with the rail the row stays level with the rail minimize outside a share`() {
        
        val rail = resolveSideRail(860f, 608f, statusBandDp = 32f, cutouts = listOf(foldLandscapeCamera))!!
        assertEquals(60f, rail.topInset.value, 0f)
        assertEquals(60f, titleRowTopDp(listOf(foldLandscapeCamera), 860f, 608f, 32f, rail, alignWithRail = true), 0f)
    }

    @Test
    fun `with the rail a share leaves the row at the top when only the rail band meets the cutout`() {
        
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
        
        assertEquals(40f, speakerBannerTopDp(listOf(Rect(500f, 0f, 540f, 40f)), 608f, 860f, 0f, isRtl = false), 0f)
        
        assertEquals(17f, speakerBannerTopDp(listOf(foldPortraitCamera), 608f, 860f, 0f, isRtl = false), 0f)
        
        assertEquals(foldPortraitCamera.bottom, speakerBannerTopDp(listOf(foldPortraitCamera), 608f, 860f, 0f, isRtl = true), 0f)
    }

    @Test
    fun `beside the rail the banner ignores a cutout in the rail band`() {
        assertEquals(17f, speakerBannerTopDp(listOf(foldLandscapeCamera), 860f, 608f, endGapDp = 84f, isRtl = false), 0f)
    }

    private val foldPortraitCamera = Rect(0f, 0f, 164f / 2.75f, 140f / 2.75f)
    private val foldLandscapeCamera = Rect(809f, 0f, 860f, 60f)
}
