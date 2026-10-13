package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Elevation of the selection handles in the overlay. The message action menu is drawn one step
 * above it so it always covers the handles and highlight (#1294).
 */
internal const val SELECTION_HANDLE_ELEVATION_PX = 10f

/**
 * Selection-edge rows in overlay-container coordinates (== root-relative, #1294).
 *
 * Horizontal: the TextView content box. Vertical: the edge line extended to its handle's visual
 * knob (start knob above the line, end knob below it), excluding the handle's touch padding.
 * A row is null exactly when its handle is GONE. Rows are NOT clipped to the list's visible area
 * here; MessageActionPopupPositioner clips them once.
 */
data class SelectionRowRects(val start: Rect?, val end: Rect?) {
    companion object {
        val NONE = SelectionRowRects(null, null)
    }
}

/** Vertical visible span in overlay-container coordinates: handle visibility and the handle's edge clip. */
internal data class VerticalClip(val top: Int, val bottom: Int) {

    fun intersects(spanTop: Int, spanBottom: Int): Boolean = spanBottom > top && spanTop < bottom

    companion object {
        val UNBOUNDED = VerticalClip(Int.MIN_VALUE, Int.MAX_VALUE)

        fun of(clipView: View?, container: View): VerticalClip {
            // Early return avoids Int.MIN_VALUE - containerTop overflow.
            clipView ?: return UNBOUNDED
            val box = clipView.clipBoxOnScreen(Rect())
            val containerTop = IntArray(2).also(container::getLocationOnScreen)[1]
            return VerticalClip(box.top - containerTop, box.bottom - containerTop)
        }
    }
}

/**
 * This view's clip box on screen: its bounds, inset by its padding when it clips to padding (a
 * RecyclerView by default), so the selection UI stops where the list content does.
 */
internal fun View.clipBoxOnScreen(out: Rect, location: IntArray = IntArray(2)): Rect {
    getLocationOnScreen(location)
    out.set(location[0], location[1], location[0] + width, location[1] + height)
    if ((this as? ViewGroup)?.clipToPadding == true) {
        out.left += paddingLeft
        out.top += paddingTop
        out.right -= paddingRight
        out.bottom -= paddingBottom
    }
    return out
}

/** One selection edge in TextView-content coordinates: caret x plus its line's vertical span. */
internal data class EdgeLine(val x: Float, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

internal fun Layout.edgeLineFor(offset: Int): EdgeLine {
    val line = getLineForOffset(offset)
    return EdgeLine(getPrimaryHorizontal(offset), getLineTop(line), getLineBottom(line))
}

/** Top-left of this TextView's content box (inside padding) in [container] coordinates. */
internal fun TextView.contentOriginIn(container: View): Point {
    val tv = IntArray(2).also(::getLocationOnScreen)
    val c = IntArray(2).also(container::getLocationOnScreen)
    return Point(tv[0] - c[0] + paddingLeft, tv[1] - c[1] + paddingTop)
}

internal fun EdgeLine.toRowRect(origin: Point, contentWidth: Int): Rect =
    Rect(origin.x, origin.y + top, origin.x + contentWidth, origin.y + bottom)

/** Extends the row to cover the handle knob: upward for the start handle, downward for the end handle. */
internal fun Rect.withHandleKnob(visualExtentPx: Int, isStart: Boolean): Rect =
    if (isStart) {
        Rect(left, min(top, bottom - visualExtentPx), right, bottom)
    } else {
        Rect(left, top, right, max(bottom, top + visualExtentPx))
    }
