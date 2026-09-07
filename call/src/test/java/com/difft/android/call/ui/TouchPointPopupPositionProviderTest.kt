package com.difft.android.call.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TouchPointPopupPositionProvider] must reproduce `ChativePopupWindow.showAtTouchPosition`:
 * centred on the touch X, top edge on the touch Y, clamped inside the window.
 */
class TouchPointPopupPositionProviderTest {

    private val window = IntSize(1080, 2400)
    private val popup = IntSize(300, 120)
    private val anchor = IntRect(left = 100, top = 1000, right = 500, bottom = 1200)

    private fun position(touchInAnchor: IntOffset): IntOffset =
        TouchPointPopupPositionProvider(touchInAnchor)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

    @Test
    fun `centres horizontally on the touch and puts the top edge on the touch`() {
        val pos = position(IntOffset(200, 50))
        assertEquals(100 + 200 - popup.width / 2, pos.x)
        assertEquals(1000 + 50, pos.y)
    }

    @Test
    fun `touch near the left edge clamps to x = 0`() {
        val pos = position(IntOffset(-100, 0))
        assertEquals(0, pos.x)
    }

    @Test
    fun `touch near the right edge clamps so the popup stays inside the window`() {
        val pos = position(IntOffset(anchor.width + 700, 0))
        assertEquals(window.width - popup.width, pos.x)
    }

    @Test
    fun `touch near the bottom aligns the popup to the window bottom`() {
        val pos = position(IntOffset(0, 1350))
        assertEquals(window.height - popup.height, pos.y)
    }

    @Test
    fun `popup larger than the window degrades to the origin instead of a negative offset`() {
        val pos = TouchPointPopupPositionProvider(IntOffset(0, 0))
            .calculatePosition(anchor, IntSize(200, 100), LayoutDirection.Ltr, popup)
        assertEquals(IntOffset(0, 0), pos)
    }
}
