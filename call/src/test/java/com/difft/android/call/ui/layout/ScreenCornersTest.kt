package com.difft.android.call.ui.layout

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.difft.android.call.layout.TileRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize






class ScreenCornersTest {

    private val proMax = LayoutSize(440.0, 956.0)

    private fun assertCorners(expected: List<Double>, actual: CornerRadii?, eps: Double = 0.01) {
        requireNotNull(actual) { "expected $expected, got null" }
        val a = listOf(actual.topLeft, actual.topRight, actual.bottomRight, actual.bottomLeft)
        assertTrue("$expected != $a", expected.indices.all { abs(expected[it] - a[it]) < eps })
    }

    

    @Test
    fun `a card at the top left rounds that corner to the screen's less its gap`() {
        assertCorners(listOf(50.0, 12.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 8.0, 204.0, 300.0), proMax, CornerRadii(62.0), 12.0))
        
        assertCorners(listOf(50.0, 50.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 8.0, 416.0, 308.0), proMax, CornerRadii(62.0), 12.0))
        assertCorners(listOf(12.0, 12.0, 50.0, 12.0), concentricCorners(LayoutRect(224.0, 640.0, 204.0, 308.0), proMax, CornerRadii(62.0), 12.0))
    }

    @Test
    fun `a card away from the screen's corners keeps its own radius`() {
        assertCorners(listOf(12.0, 12.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 114.0, 416.0, 300.0), proMax, CornerRadii(62.0), 12.0))
        
        assertCorners(listOf(12.0, 12.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 8.0, 351.0, 300.0), LayoutSize(375.0, 667.0), CornerRadii(0.0), 12.0))
        
        assertCorners(listOf(12.0, 12.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 8.0, 100.0, 100.0), LayoutSize(411.0, 914.0), CornerRadii(10.7), 12.0))
    }

    @Test
    fun `each corner takes the screen's radius at that corner`() {
        
        val win = LayoutSize(466.0, 678.0)
        val screen = CornerRadii(8.0, 59.0, 59.0, 8.0)
        assertCorners(listOf(12.0, 47.0, 12.0, 12.0), concentricCorners(LayoutRect(12.0, 8.0, 442.0, 327.0), win, screen, 12.0))
        assertCorners(listOf(12.0, 12.0, 47.0, 12.0), concentricCorners(LayoutRect(12.0, 343.0, 442.0, 327.0), win, screen, 12.0))
    }

    @Test
    fun `a corner never rounds past half the card's shorter side`() {
        
        assertCorners(listOf(12.0, 12.0, 30.0, 12.0), concentricCorners(LayoutRect(440.0 - 60, 956.0 - 80, 60.0, 80.0), proMax, CornerRadii(62.0), 12.0))
    }

    @Test
    fun `the radius follows a card moving into the corner without a jump`() {
        
        var last = 12.0
        for (step in 0..20) {
            val gap = 60.0 - step * 2.5 
            val r = concentricCorners(LayoutRect(gap, gap, 120.0, 160.0), proMax, CornerRadii(51.6), 12.0).topLeft
            assertTrue("step $step: $r < $last", r >= last - 1e-9)
            assertTrue("step $step: jumped from $last to $r", r - last <= 2.5 + 1e-9)
            last = r
        }
        assertEquals(41.6, last, 1e-9)
    }

    

    @Test
    fun `a name in a rounded corner moves in until inside the curve`() {
        
        val r = 51.6 - 12
        val a = insetInCorner(4.0, 4.33, r, 12.0)
        assertEquals(r - sqrt(r * r - (r - 4.33) * (r - 4.33)) + 2, a, 1e-9)
        
        assertEquals(r * r, (r - (a - 2)) * (r - (a - 2)) + (r - 4.33) * (r - 4.33), 1e-6)
        
        assertEquals(50 - sqrt(50.0 * 50 - 44.0 * 44) + 2, insetInCorner(6.0, 6.0, 50.0, 12.0), 1e-9)
    }

    @Test
    fun `a long name's tail stops 12 from the end, further in before a rounded corner`() {
        
        
        val r = 48.0
        assertEquals(r - sqrt(r * r - (r - 4.33) * (r - 4.33)) + 2 - 12, cornerShift(12.0, 4.33, r).value.toDouble(), 1e-3)
        
        assertEquals(0f, cornerShift(12.0, 4.33, 18.0).value, 0f)
        assertEquals(0f, cornerShift(12.0, 4.33, null).value, 0f)
    }

    @Test
    fun `a corner no rounder than the card's own leaves it be, as does one it already clears`() {
        assertEquals(4.0, insetInCorner(4.0, 4.33, 12.0, 12.0), 0.0)
        assertEquals(8.0, insetInCorner(8.0, 60.0, 50.0, 12.0), 0.0)
        assertEquals(30.0, insetInCorner(30.0, 8.0, 50.0, 12.0), 0.0)
    }

    

    @Test
    fun `rounded corners turn into dp corner by corner`() {
        
        assertCorners(listOf(10.18, 56.0, 56.0, 10.18), roundedCornerRadii(listOf(28, 154, 154, 28), 2.75))
        
        assertCorners(listOf(30.46, 30.46, 30.46, 30.46), roundedCornerRadii(listOf(99, 99, 99, 99), 3.25))
    }

    @Test
    fun `a corner the system leaves out is square`() {
        assertCorners(listOf(30.46, 0.0, 30.46, 30.46), roundedCornerRadii(listOf(99, null, 99, 99), 3.25))
    }

    @Test
    fun `no rounded corner anywhere means no screen corners`() {
        assertNull(roundedCornerRadii(listOf(null, null, null, null), 3.0))
        assertNull(roundedCornerRadii(listOf(0, 0, 0, null), 3.0))
    }

    

    private val screen = IntRect(0, 0, 1080, 2400)

    @Test
    fun `a window as large as the screen and not split is the whole screen`() {
        assertTrue(isWholeScreen(current = screen, maximum = screen, multiWindow = false, view = IntSize(1080, 2400)))
    }

    @Test
    fun `split screen, a smaller window or a window that is not yet laid out is not the whole screen`() {
        assertFalse(isWholeScreen(current = screen, maximum = screen, multiWindow = true, view = IntSize(1080, 2400)))
        assertFalse(isWholeScreen(current = IntRect(0, 0, 1080, 1190), maximum = screen, multiWindow = false, view = IntSize(1080, 1190)))
        
        val turned = IntRect(0, 0, 2400, 1080)
        assertFalse(isWholeScreen(current = turned, maximum = turned, multiWindow = false, view = IntSize(1080, 2400)))
        
        assertTrue(isWholeScreen(current = screen, maximum = screen, multiWindow = false, view = IntSize(1079, 2400)))
    }

    @Test
    fun `screen corners count only on the whole screen with some corner rounded`() {
        val window = LayoutSize(392.7, 872.7)
        val radii = CornerRadii(30.5)
        assertEquals(CallScreenCorners(window, radii), callScreenCorners(radii, wholeScreen = true, window = window))
        assertNull(callScreenCorners(radii, wholeScreen = false, window = window))
        assertNull(callScreenCorners(null, wholeScreen = true, window = window))
    }

    

    @Test
    fun `grid cards and the floating window round, cards that fill the window and the share do not`() {
        assertTrue(takesScreenCorners(TileRole.GRID, pipFocus = false, hidden = false))
        assertTrue(takesScreenCorners(TileRole.PIP, pipFocus = false, hidden = false))
        assertFalse(takesScreenCorners(TileRole.MAIN, pipFocus = false, hidden = false))
        assertFalse(takesScreenCorners(TileRole.SOLO, pipFocus = false, hidden = false))
        assertFalse(takesScreenCorners(TileRole.SHARE, pipFocus = false, hidden = false))
        
        assertFalse(takesScreenCorners(TileRole.GRID, pipFocus = true, hidden = false))
        assertFalse(takesScreenCorners(TileRole.PIP, pipFocus = false, hidden = true))
    }

    @Test
    fun `a card's corners are null where it keeps its own all round`() {
        val corners = CallScreenCorners(proMax, CornerRadii(62.0))
        assertCorners(listOf(50.0, 12.0, 12.0, 12.0), cardCorners(LayoutRect(12.0, 8.0, 204.0, 300.0), corners))
        assertNull(cardCorners(LayoutRect(12.0, 114.0, 416.0, 300.0), corners))
        assertNull(cardCorners(LayoutRect(12.0, 8.0, 204.0, 300.0), null))
    }

    @Test
    fun `the start and end corners follow the layout direction`() {
        val c = CornerRadii(1.0, 2.0, 3.0, 4.0)
        assertEquals(4.0, c.bottomStart(rtl = false), 0.0)
        assertEquals(3.0, c.bottomStart(rtl = true), 0.0)
        assertEquals(2.0, c.topEnd(rtl = false), 0.0)
        assertEquals(1.0, c.topEnd(rtl = true), 0.0)
        assertEquals(3.0, c.bottomEnd(rtl = false), 0.0)
        assertEquals(4.0, c.bottomEnd(rtl = true), 0.0)
    }
}
