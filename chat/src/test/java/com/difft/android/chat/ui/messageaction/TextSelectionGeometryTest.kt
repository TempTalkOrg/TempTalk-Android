package com.difft.android.chat.ui.messageaction

import android.app.Activity
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G3-G26 (#1294): selection-handle geometry that anchors the message action menu, against a real
 * TextView / overlay FrameLayout / [SelectionHandleView] tree. G4 and G19 are characterization
 * rows: they were written and run green against the pre-refactor `updateHandlePositions()`.
 *
 * Row formulas: `startRow(i) = (O.x, O.y+Lb(i)-Ks, O.x+W, O.y+Lb(i))` and
 * `endRow(j) = (O.x, O.y+Lt(j), O.x+W, O.y+Lt(j)+Ke)`, where O is the content origin in the
 * overlay, W the content width and Ks/Ke the handles' visual extents.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w480dp-h1400dp-mdpi")
class TextSelectionGeometryTest {

    private companion object {
        const val TEXT_WIDTH = 400
        const val TEXT_SIZE_PX = 40f
        const val LINE_COUNT = 10
        val LTR_TEXT = (0 until LINE_COUNT).joinToString("\n") { "Line $it" }
        val RTL_TEXT = (0 until LINE_COUNT).joinToString("\n") { "שלום $it" }
    }

    private val controllers = mutableListOf<ActivityController<Activity>>()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
    }

    /**
     * Root FrameLayout holding a bubble (optional quote + 10-line TextView with 8dp padding),
     * an optional clip view, and the MATCH_PARENT overlay. Handles are overlay children 1 and 2.
     */
    private inner class Fixture(
        bubbleTop: Int = 0,
        quoteHeight: Int = 0,
        overlayTop: Int = 0,
        text: CharSequence = LTR_TEXT,
        rtl: Boolean = false,
    ) {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        val root = FrameLayout(activity)
        val bubble = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val clipView = View(activity)
        val padding = (8 * activity.resources.displayMetrics.density).toInt()
        val textView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_SIZE_PX)
            setPadding(padding, padding, padding, padding)
            if (rtl) {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_RTL
            }
            this.text = text
        }
        val overlay = FrameLayout(activity)
        val manager = TextSelectionManager(textView)

        init {
            if (quoteHeight > 0) bubble.addView(View(activity), LinearLayout.LayoutParams(TEXT_WIDTH, quoteHeight))
            bubble.addView(textView, LinearLayout.LayoutParams(TEXT_WIDTH, LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(
                bubble,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = bubbleTop },
            )
            root.addView(clipView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
            root.addView(
                overlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    .apply { topMargin = overlayTop },
            )
            activity.setContentView(root)
            settle()
            assertNotNull("fixture TextView must be laid out", textView.layout)
        }

        val startHandle: SelectionHandleView get() = overlay.getChildAt(1) as SelectionHandleView
        val endHandle: SelectionHandleView get() = overlay.getChildAt(2) as SelectionHandleView
        val lastLine get() = textView.layout.lineCount - 1

        /** Test-local copy of the origin arithmetic, independent of the production helper. */
        fun origin(): Point {
            val tv = IntArray(2).also(textView::getLocationOnScreen)
            val c = IntArray(2).also(overlay::getLocationOnScreen)
            return Point(tv[0] - c[0] + textView.paddingLeft, tv[1] - c[1] + textView.paddingTop)
        }

        fun lineTop(i: Int) = textView.layout.getLineTop(i)
        fun lineBottom(i: Int) = textView.layout.getLineBottom(i)
        private fun contentWidth() = textView.width - textView.paddingLeft - textView.paddingRight

        fun startRow(i: Int): Rect {
            val o = origin()
            val ks = startHandle.visualExtentPx.toInt()
            return Rect(o.x, o.y + lineBottom(i) - ks, o.x + contentWidth(), o.y + lineBottom(i))
        }

        fun endRow(j: Int): Rect {
            val o = origin()
            val ke = endHandle.visualExtentPx.toInt()
            return Rect(o.x, o.y + lineTop(j), o.x + contentWidth(), o.y + lineTop(j) + ke)
        }

        /** Sizes the clip view to exactly cover lines [first]..[last] (overlay at root top). */
        fun clipToLines(first: Int, last: Int) {
            clipView.layoutParams = clipView.layoutParams.apply { height = lineBottom(last) - lineTop(first) }
            settle()
            clipView.translationY = (origin().y + lineTop(first)).toFloat()
        }

        fun attachWithClip() = manager.attachToOverlay(overlay, clipView)
    }

    private class DragRecorder : TextSelectionManager.HandleDragCallback {
        val started = mutableListOf<Boolean>()
        var drags = 0
        var ends = 0
        var onEnd: (() -> Unit)? = null
        override fun onHandleDrag(screenY: Float, isStart: Boolean) {
            drags++
        }
        override fun onHandleDragStarted(isStart: Boolean) {
            started += isStart
        }
        override fun onHandleDragEnd() {
            ends++
            onEnd?.invoke()
        }
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** DOWN at (0,0), one MOVE per entry of [moveYs] (absolute raw Y), then UP. */
    private fun gesture(handle: View, vararg moveYs: Float, afterMove: (Int) -> Unit = {}) {
        touch(handle, MotionEvent.ACTION_DOWN, 0f, 0f)
        moveYs.forEachIndexed { index, y ->
            touch(handle, MotionEvent.ACTION_MOVE, 0f, y)
            afterMove(index)
        }
        touch(handle, MotionEvent.ACTION_UP, 0f, moveYs.lastOrNull() ?: 0f)
    }

    private fun assertStartPlacedAt(f: Fixture, line: Int, offset: Int) {
        val o = f.origin()
        val h = f.startHandle
        val params = h.layoutParams as FrameLayout.LayoutParams
        val primary = f.textView.layout.getPrimaryHorizontal(offset)
        assertEquals((o.x + primary - h.pivotOffsetX).toInt(), params.leftMargin)
        assertEquals((o.y + f.lineBottom(line).toFloat() - h.pivotOffsetY).toInt(), params.topMargin)
        assertEquals(View.VISIBLE, h.visibility)
        assertEquals(0f, h.translationX)
        assertEquals(0f, h.translationY)
    }

    private fun assertEndPlacedAt(f: Fixture, line: Int, offset: Int) {
        val o = f.origin()
        val h = f.endHandle
        val params = h.layoutParams as FrameLayout.LayoutParams
        val primary = f.textView.layout.getPrimaryHorizontal(offset)
        assertEquals((o.x + primary - h.pivotOffsetX).toInt(), params.leftMargin)
        assertEquals((o.y + f.lineTop(line).toFloat() - h.pivotOffsetY).toInt(), params.topMargin)
        assertEquals(View.VISIBLE, h.visibility)
        assertEquals(0f, h.translationX)
        assertEquals(0f, h.translationY)
    }

    @Test
    fun `G3 VerticalClip of converts the clip view into container space`() {
        val f = Fixture()
        assertEquals(VerticalClip.UNBOUNDED, VerticalClip.of(null, f.overlay))

        f.clipView.layoutParams = f.clipView.layoutParams.apply { height = 400 }
        settle()
        f.clipView.translationY = 200f
        assertEquals(VerticalClip(200, 600), VerticalClip.of(f.clipView, f.overlay))

        f.overlay.translationY = 50f
        assertEquals(VerticalClip(150, 550), VerticalClip.of(f.clipView, f.overlay))
    }

    /** The clip view is a plain View here; a padded ViewGroup that clips to padding insets the clip. */
    @Test
    fun `G24 a clip view that clips to padding insets the clip and the highlight`() {
        val f = Fixture()
        val padded = FrameLayout(f.activity).apply { setPadding(0, 30, 0, 50) }
        f.root.addView(padded, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 400))
        settle()
        padded.translationY = 100f
        val containerTop = IntArray(2).also(f.overlay::getLocationOnScreen)[1]
        val paddedTop = IntArray(2).also(padded::getLocationOnScreen)[1]
        assertEquals(VerticalClip(paddedTop + 30 - containerTop, paddedTop + 400 - 50 - containerTop), VerticalClip.of(padded, f.overlay))

        f.manager.attachToOverlay(f.overlay, padded)
        f.manager.selectAll()
        val textTop = IntArray(2).also(f.textView::getLocationOnScreen)[1]
        val highlight = f.overlay.getChildAt(0)
        assertEquals((paddedTop + 30 - textTop).coerceAtLeast(0), requireNotNull(highlight.clipBounds).top)
        assertEquals((paddedTop + 400 - 50 - textTop).coerceAtMost(f.textView.height), requireNotNull(highlight.clipBounds).bottom)

        padded.clipToPadding = false
        f.manager.updateUIAfterScroll()
        assertEquals(VerticalClip(paddedTop - containerTop, paddedTop + 400 - containerTop), VerticalClip.of(padded, f.overlay))
        assertEquals((paddedTop - textTop).coerceAtLeast(0), requireNotNull(highlight.clipBounds).top)
    }

    /**
     * A handle whose drawn part is above the clip is GONE even when its touch padding dips into the
     * clip (it used to be drawn whole over the toolbar); one straddling the edge is cut at the edge.
     */
    @Test
    fun `G25 handle visibility follows the drawn part and straddling handles are cut at the clip edge`() {
        val f = Fixture()
        f.clipToLines(0, 3)
        f.attachWithClip()
        f.manager.selectAll()
        val h = f.startHandle
        val handleTop = (h.layoutParams as FrameLayout.LayoutParams).topMargin
        val drawnTop = handleTop + h.visualTopInset.toInt()
        val drawnBottom = drawnTop + h.visualExtentPx.toInt()

        // The start circle sits above its line, so the clip must start above the drawn part.
        f.clipView.translationY = (drawnTop - 10).toFloat()
        f.manager.updateUIAfterScroll()
        assertEquals(View.VISIBLE, h.visibility)
        assertNull("fully inside the clip: no local clip", h.clipBounds)

        // Clip top halfway through the drawn part: visible, cut at the clip edge.
        val halfway = (drawnTop + drawnBottom) / 2
        f.clipView.translationY = halfway.toFloat()
        f.manager.updateUIAfterScroll()
        assertEquals(View.VISIBLE, h.visibility)
        val cut = requireNotNull(h.clipBounds)
        assertEquals(halfway - handleTop, cut.top)
        assertEquals(0, cut.left)
        assertEquals(h.totalWidth.toInt(), cut.right)

        // Clip top just below the drawn part: only the touch padding overlaps the clip -> GONE.
        f.clipView.translationY = (drawnBottom + 1).toFloat()
        f.manager.updateUIAfterScroll()
        assertEquals(View.GONE, h.visibility)
        assertNull(f.manager.getVisibleHandleRowRects().start)
    }

    @Test
    fun `G26 a real drag clears the dragged handle's edge clip, a tap keeps it, settling restores it`() {
        val f = Fixture()
        f.clipToLines(0, 3)
        f.attachWithClip()
        f.manager.selectAll()
        val h = f.startHandle
        val handleTop = (h.layoutParams as FrameLayout.LayoutParams).topMargin
        f.clipView.translationY = (handleTop + h.visualTopInset.toInt() + 2).toFloat()
        f.manager.updateUIAfterScroll()
        val cut = requireNotNull(h.clipBounds)
        val slop = ViewConfiguration.get(f.activity).scaledTouchSlop

        touch(h, MotionEvent.ACTION_DOWN, 0f, 0f)
        touch(h, MotionEvent.ACTION_MOVE, 0f, (slop - 1).toFloat())
        assertEquals("a sub-slop touch keeps the cut", cut, h.clipBounds)
        touch(h, MotionEvent.ACTION_MOVE, 0f, (3 * slop).toFloat())
        assertNull("the dragged handle is drawn whole", h.clipBounds)
        touch(h, MotionEvent.ACTION_UP, 0f, (3 * slop).toFloat())
        assertNotNull("settling recomputes the edge clip", h.clipBounds)
    }

    @Test
    fun `G4 characterization - selectAll with no clip places both handles on the edge lines`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        assertStartPlacedAt(f, line = 0, offset = 0)
        assertEndPlacedAt(f, line = f.lastLine, offset = f.textView.text.length)
    }

    @Test
    fun `G5 no handle visible yields NONE`() {
        val f = Fixture()
        f.clipToLines(3, 6)
        f.attachWithClip()
        f.manager.selectAll()

        assertEquals(View.GONE, f.startHandle.visibility)
        assertEquals(View.GONE, f.endHandle.visibility)
        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
    }

    @Test
    fun `G6 only the start handle visible`() {
        val f = Fixture()
        f.clipToLines(0, 4)
        f.attachWithClip()
        f.manager.selectAll()

        val rows = f.manager.getVisibleHandleRowRects()
        assertEquals(f.startRow(0), rows.start)
        assertNull(rows.end)
    }

    @Test
    fun `G7 only the end handle visible`() {
        val f = Fixture()
        f.clipToLines(5, 9)
        f.attachWithClip()
        f.manager.selectAll()

        val rows = f.manager.getVisibleHandleRowRects()
        assertNull(rows.start)
        assertEquals(f.endRow(f.lastLine), rows.end)
    }

    @Test
    fun `G8 same-line selection gives overlapping rows on that line`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.setSelection(2, 5)

        val rows = f.manager.getVisibleHandleRowRects()
        val o = f.origin()
        val start = requireNotNull(rows.start)
        val end = requireNotNull(rows.end)
        assertEquals(f.startRow(0), start)
        assertEquals(f.endRow(0), end)
        assertEquals(o.y + f.lineBottom(0), start.bottom)
        assertEquals(o.y + f.lineTop(0), end.top)
        assertTrue("same-line rows overlap vertically", start.top < end.bottom && end.top < start.bottom)
    }

    @Test
    fun `G9 different-line selection gives one row per edge line`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        val layout = f.textView.layout
        f.manager.setSelection(layout.getLineStart(2) + 1, layout.getLineStart(6) + 3)

        val rows = f.manager.getVisibleHandleRowRects()
        val start = requireNotNull(rows.start)
        val end = requireNotNull(rows.end)
        assertEquals(f.startRow(2), start)
        assertEquals(f.endRow(6), end)
        assertTrue(start.bottom <= end.top)
    }

    @Test
    fun `G10 a row exists exactly when its handle is visible and rows are never clipped`() {
        val f = Fixture()
        f.clipToLines(0, 3)
        f.attachWithClip()
        f.manager.selectAll()
        val textHeight = f.textView.layout.height
        val step = (f.lineBottom(0) - f.lineTop(0)) / 2
        val contentTop = f.origin().y
        var sawStart = false
        var sawEnd = false
        var sawNone = false

        var clipTop = contentTop - textHeight
        while (clipTop <= contentTop + textHeight) {
            f.clipView.translationY = clipTop.toFloat()
            f.manager.updateUIAfterScroll()
            val rows = f.manager.getVisibleHandleRowRects()

            assertEquals("start @clipTop=$clipTop", f.startHandle.visibility == View.VISIBLE, rows.start != null)
            assertEquals("end @clipTop=$clipTop", f.endHandle.visibility == View.VISIBLE, rows.end != null)
            rows.start?.let { assertEquals(f.startRow(0), it) }
            rows.end?.let { assertEquals(f.endRow(f.lastLine), it) }
            sawStart = sawStart || rows.start != null
            sawEnd = sawEnd || rows.end != null
            sawNone = sawNone || rows == SelectionRowRects.NONE
            clipTop += step
        }
        assertTrue("sweep must cover start-visible, end-visible and none-visible", sawStart && sawEnd && sawNone)
    }

    @Test
    fun `G11 never-measured TextView yields NONE`() {
        val f = Fixture()
        val detachedTextView = TextView(f.activity).apply { text = LTR_TEXT }
        val manager = TextSelectionManager(detachedTextView)
        manager.attachToOverlay(f.overlay)
        manager.selectAll()

        assertNull(detachedTextView.layout)
        assertEquals(SelectionRowRects.NONE, manager.getVisibleHandleRowRects())
        assertEquals(View.GONE, f.startHandle.visibility)
        assertEquals(View.GONE, f.endHandle.visibility)
    }

    @Test
    fun `G12 before attach and after detach yield NONE`() {
        val f = Fixture()
        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())

        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        assertNotEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
        f.manager.detach()
        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
    }

    @Test
    fun `G13 empty caption has no selection and yields NONE`() {
        val f = Fixture(text = "")
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        assertFalse(f.manager.hasSelection())
        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
    }

    @Test
    fun `G14 TextView no longer shown yields NONE`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        f.textView.visibility = View.GONE

        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
    }

    @Test
    fun `G15 rows are NONE while a handle is dragged`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        touch(f.endHandle, MotionEvent.ACTION_DOWN, 0f, 0f)
        assertEquals(SelectionRowRects.NONE, f.manager.getVisibleHandleRowRects())
        touch(f.endHandle, MotionEvent.ACTION_UP, 0f, 0f)
        assertEquals(f.endRow(f.lastLine), f.manager.getVisibleHandleRowRects().end)
    }

    @Test
    fun `G16 rows are overlay-local below a quote in an offset bubble`() {
        val quoteHeight = 120
        val f = Fixture(bubbleTop = 300, quoteHeight = quoteHeight)
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        val tvGlobal = Rect().also { f.textView.getGlobalVisibleRect(it) }
        val rootGlobal = Rect().also { f.root.getGlobalVisibleRect(it) }
        val bubbleGlobal = Rect().also { f.bubble.getGlobalVisibleRect(it) }
        val ks = f.startHandle.visualExtentPx.toInt()
        val start = requireNotNull(f.manager.getVisibleHandleRowRects().start)

        assertEquals(tvGlobal.top - rootGlobal.top + f.textView.paddingTop + f.lineBottom(0) - ks, start.top)
        assertTrue(start.bottom > bubbleGlobal.top - rootGlobal.top + quoteHeight)
    }

    @Test
    fun `G17 drag start fires once per gesture and only beyond the touch slop`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        val slop = ViewConfiguration.get(f.activity).scaledTouchSlop.toFloat()

        // (a) beyond the slop: started once, on the first MOVE
        val a = DragRecorder().also { f.manager.handleDragCallback = it }
        gesture(f.endHandle, 2 * slop, 2 * slop + 3) { index ->
            if (index == 0) assertEquals(listOf(false), a.started)
        }
        assertEquals(listOf(false), a.started)
        assertEquals(2, a.drags)
        assertEquals(1, a.ends)

        // (b) plain tap
        val b = DragRecorder().also { f.manager.handleDragCallback = it }
        gesture(f.endHandle)
        assertTrue(b.started.isEmpty())
        assertEquals(0, b.drags)
        assertEquals(1, b.ends)

        // (c) jitter inside the slop
        val c = DragRecorder().also { f.manager.handleDragCallback = it }
        gesture(f.endHandle, slop - 1, 0f)
        assertTrue(c.started.isEmpty())
        assertEquals(2, c.drags)
        assertEquals(1, c.ends)
    }

    @Test
    fun `G18 drag end notifies selection before drag end with the final rows`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        val events = mutableListOf<String>()
        var rowsAtEnd: SelectionRowRects? = null
        f.manager.onSelectionChanged = object : TextSelectionManager.SelectionCallback {
            override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                events += "selection"
            }
        }
        f.manager.handleDragCallback = DragRecorder().apply {
            onEnd = {
                events += "dragEnd"
                rowsAtEnd = f.manager.getVisibleHandleRowRects()
            }
        }
        val lineHeight = (f.lineBottom(0) - f.lineTop(0)).toFloat()

        gesture(f.endHandle, -2 * lineHeight)

        assertEquals(listOf("selection", "dragEnd"), events)
        val endLine = f.textView.layout.getLineForOffset(f.manager.getSelectionEnd())
        assertTrue("drag must move the end up", endLine < f.lastLine)
        assertEquals(f.endRow(endLine), rowsAtEnd?.end)
    }

    @Test
    fun `G19 characterization - no clip view keeps handles visible when lines scroll above the overlay`() {
        val probe = Fixture()
        val overlayTop = probe.textView.paddingTop + probe.lineBottom(2)
        val f = Fixture(overlayTop = overlayTop)
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        f.manager.updateUIAfterScroll()

        assertStartPlacedAt(f, line = 0, offset = 0)
        assertEndPlacedAt(f, line = f.lastLine, offset = f.textView.text.length)
    }

    @Test
    fun `G20 RTL rows span the content box`() {
        val f = Fixture(text = RTL_TEXT, rtl = true)
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        val o = f.origin()
        val w = f.textView.width - f.textView.paddingLeft - f.textView.paddingRight
        val start = requireNotNull(f.manager.getVisibleHandleRowRects().start)
        assertEquals(o.x, start.left)
        assertEquals(o.x + w, start.right)
        assertEquals(f.startRow(0), start)
    }

    @Test
    fun `G21 rows extend to the handle knobs`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()

        val o = f.origin()
        val ks = f.startHandle.visualExtentPx.toInt()
        val ke = f.endHandle.visualExtentPx.toInt()
        val rows = f.manager.getVisibleHandleRowRects()
        val start = requireNotNull(rows.start)
        val end = requireNotNull(rows.end)
        assertEquals(o.y + f.lineBottom(0) - ks, start.top)
        assertTrue(start.top <= o.y + f.lineTop(0))
        assertEquals(o.y + f.lineTop(f.lastLine) + ke, end.bottom)
        assertTrue(end.bottom >= o.y + f.lineBottom(f.lastLine))
    }

    @Test
    fun `G22 withHandleKnob only ever grows the row`() {
        val row = Rect(0, 100, 50, 140)
        assertEquals(Rect(0, 80, 50, 140), row.withHandleKnob(60, isStart = true))
        assertEquals(Rect(0, 100, 50, 160), row.withHandleKnob(60, isStart = false))
        assertEquals(row, row.withHandleKnob(20, isStart = true))
        assertEquals(row, row.withHandleKnob(20, isStart = false))
    }

    @Test
    fun `G23 drag start state resets for the next gesture`() {
        val f = Fixture()
        f.manager.attachToOverlay(f.overlay)
        f.manager.selectAll()
        val slop = ViewConfiguration.get(f.activity).scaledTouchSlop.toFloat()
        val recorder = DragRecorder().also { f.manager.handleDragCallback = it }

        gesture(f.endHandle, 2 * slop, 2 * slop + 3)
        gesture(f.startHandle, 2 * slop)

        assertEquals(listOf(false, true), recorder.started)
    }
}
