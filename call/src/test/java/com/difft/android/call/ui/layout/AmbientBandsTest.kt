package com.difft.android.call.ui.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.difft.android.call.layout.CallType
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.LayoutInput
import com.difft.android.call.layout.Participant
import com.difft.android.call.layout.Tile
import com.difft.android.call.layout.TileRole
import com.difft.android.call.layout.resolveLayout
import com.difft.android.call.ui.video.EdgeColors
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Rect as PxRect
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize

private const val EPS = 1e-6
private const val PORTRAIT = 9.0 / 16


class AmbientBandsTest {

    private fun groupInput(vararg people: Participant) = LayoutInput(
        window = LayoutSize(393.0, 852.0),
        content = LayoutRect(0.0, 0.0, 393.0, 852.0),
        callType = CallType.GROUP,
        participants = people.toList(),
    )

    private fun card(rect: LayoutRect, mode: ContentMode? = ContentMode.FIT, role: TileRole = TileRole.GRID) =
        Tile(id = "p1", role = role, rect = rect, contentMode = mode, mismatch = 0.0, z = 0)

    private val portrait = Participant(id = "p1", isLocal = false, videoOn = true, videoAspect = PORTRAIT)

    @Test
    fun `a card shaped like its video has no band`() {
        val band = ambientBandOf(card(LayoutRect(0.0, 0.0, 90.0, 160.0)), groupInput(portrait))
        assertEquals(0.0, band, EPS)
    }

    @Test
    fun `a card widened past a portrait video has side bands of half the extra width`() {
        val band = ambientBandOf(card(LayoutRect(10.0, 20.0, 130.0, 160.0)), groupInput(portrait))
        assertEquals(20.0, band, EPS)
        assertTrue(band >= AMBIENT_MIN_BAND_DP)
    }

    @Test
    fun `filled, avatar and unknown-shape tiles have none`() {
        val rect = LayoutRect(0.0, 0.0, 130.0, 160.0)
        assertEquals(0.0, ambientBandOf(card(rect, ContentMode.FILL), groupInput(portrait)), EPS)
        assertEquals(0.0, ambientBandOf(card(rect, null), groupInput(portrait)), EPS)
        assertEquals(0.0, ambientBandOf(card(rect), groupInput(portrait.copy(videoAspect = null))), EPS)
        assertEquals(0.0, ambientBandOf(card(rect), null), EPS)
    }

    @Test
    fun `a floating window widened past its video has side bands like a card`() {
        
        val band = ambientBandOf(card(LayoutRect(10.0, 20.0, 130.0, 160.0), role = TileRole.PIP), groupInput(portrait))
        assertEquals(20.0, band, EPS)
        assertEquals(0.0, ambientBandOf(card(LayoutRect(10.0, 20.0, 130.0, 160.0), ContentMode.FILL, TileRole.PIP), groupInput(portrait)), EPS)
    }

    @Test
    fun `the 1on1 main view's bands are the window around its card, the video itself`() {
        
        val input = LayoutInput(
            window = LayoutSize(852.0, 393.0),
            content = LayoutRect(0.0, 0.0, 852.0, 393.0),
            callType = CallType.ONE_ON_ONE,
            participants = listOf(Participant(id = "me", isLocal = true, videoOn = false), portrait),
        )
        val main = resolveLayout(input).tiles.single { it.role == TileRole.MAIN }
        
        assertEquals((852.0 - 393.0 * PORTRAIT) / 2, ambientBandOf(main, input), 0.01)
    }

    @Test
    fun `side bands lie between the top and bottom bands, which run the full width`() {
        val box = PxRect(0f, 0f, 200f, 100f)
        val sides = bandsOf(box, PxRect(50f, 0f, 150f, 100f))
        assertEquals(listOf(BandSide.LEFT, BandSide.RIGHT), sides.map { it.side })
        assertEquals(PxRect(0f, 0f, 50f, 100f), sides[0].rect)
        assertEquals(PxRect(150f, 0f, 200f, 100f), sides[1].rect)

        val all = bandsOf(box, PxRect(40f, 10f, 160f, 90f))
        assertEquals(listOf(BandSide.TOP, BandSide.BOTTOM, BandSide.LEFT, BandSide.RIGHT), all.map { it.side })
        assertEquals(PxRect(0f, 0f, 200f, 10f), all[0].rect)
        assertEquals(PxRect(0f, 10f, 40f, 90f), all[2].rect)
    }

    @Test
    fun `a band under a pixel is left out, and an off-centre picture gets uneven bands`() {
        val bands = bandsOf(PxRect(0f, 0f, 200f, 100f), PxRect(0.5f, 0f, 120f, 100f))
        assertEquals(listOf(BandSide.RIGHT), bands.map { it.side })
        assertEquals(80f, bands.single().rect.width)
    }

    @Test
    fun `the picture is fitted whole and centred, as the renderer measures it`() {
        val picture = fittedPicture(PxRect(Offset(10f, 0f), Size(200f, 160f)), 9f / 16)
        assertEquals(PxRect(Offset(65f, 0f), Size(90f, 160f)), picture)
        val box = PxRect(0f, 0f, 100f, 100f)
        assertSame("no shape known", box, fittedPicture(box, 0f))
    }

    @Test
    fun `colours ease from one sample to the next`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        fun edges(c: Int, aspect: Float) = EdgeColors(aspect, IntArray(8) { c }, IntArray(8) { c }, IntArray(8) { c }, IntArray(8) { c })
        val from = edges(black, 1f)
        val to = edges(white, 2f)
        assertSame(to, lerpEdges(from, to, 1f))
        assertEquals(black, lerpEdges(from, to, 0f).top[0])
        val mid = lerpEdges(from, to, 0.5f)
        assertEquals("the new shape at once", 2f, mid.aspect)
        assertTrue("halfway is grey", mid.left[3] != black && mid.left[3] != white)
    }

    @Test
    fun `a sample of the same shape eases in, a new shape or the first sample takes its colours at once`() {
        fun edges(aspect: Float) = EdgeColors(aspect, IntArray(8), IntArray(8), IntArray(8), IntArray(8))
        assertTrue(easesBetween(edges(PORTRAIT.toFloat()), edges(PORTRAIT.toFloat() + 0.005f)))
        assertFalse("the far end turned", easesBetween(edges(PORTRAIT.toFloat()), edges(16f / 9)))
        assertFalse("nothing to ease from", easesBetween(null, edges(1f)))
    }

    @Test
    fun `the 1on1 main card draws bands inside itself too, while its video shows`() {
        val main = CallTileStyle(TileRole.MAIN, ContentMode.FIT, DpSize(300.dp, 400.dp), ambient = true)
        assertTrue("its card keeps the old shape while the new one settles", cardAmbientShown(main, showVideo = true))
        assertFalse(cardAmbientShown(main, showVideo = false))
        assertFalse(cardAmbientShown(main.copy(ambient = false), showVideo = true))
        assertTrue(cardAmbientShown(main.copy(role = TileRole.GRID), showVideo = true))
    }
}
