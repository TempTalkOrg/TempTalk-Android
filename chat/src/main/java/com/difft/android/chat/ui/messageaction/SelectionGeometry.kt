package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min





internal const val SELECTION_HANDLE_ELEVATION_PX = 10f









data class SelectionRowRects(val start: Rect?, val end: Rect?) {
    companion object {
        val NONE = SelectionRowRects(null, null)
    }
}


internal data class VerticalClip(val top: Int, val bottom: Int) {

    fun intersects(spanTop: Int, spanBottom: Int): Boolean = spanBottom > top && spanTop < bottom

    companion object {
        val UNBOUNDED = VerticalClip(Int.MIN_VALUE, Int.MAX_VALUE)

        fun of(clipView: View?, container: View): VerticalClip {
            
            clipView ?: return UNBOUNDED
            val box = clipView.clipBoxOnScreen(Rect())
            val containerTop = IntArray(2).also(container::getLocationOnScreen)[1]
            return VerticalClip(box.top - containerTop, box.bottom - containerTop)
        }
    }
}






internal fun View.clipBoxOnScreen(out: Rect, location: IntArray = IntArray(2)): Rect {
    getLocationOnScreen(location)
    out.set(location[0], location[1], location[0] + width, location[1] + height)
    val clipsPadding = (this as? ViewGroup)?.clipToPadding == true
    val chrome = ChatContentBand.insetsOf(this)
    if (clipsPadding) {
        out.left += paddingLeft
        out.right -= paddingRight
    }
    out.top += max(if (clipsPadding) paddingTop else 0, chrome.top)
    out.bottom -= max(if (clipsPadding) paddingBottom else 0, chrome.bottom)
    return out
}


internal data class EdgeLine(val x: Float, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

internal fun Layout.edgeLineFor(offset: Int): EdgeLine {
    val line = getLineForOffset(offset)
    return EdgeLine(getPrimaryHorizontal(offset), getLineTop(line), getLineBottom(line))
}


internal fun TextView.contentOriginIn(container: View): Point {
    val tv = IntArray(2).also(::getLocationOnScreen)
    val c = IntArray(2).also(container::getLocationOnScreen)
    return Point(tv[0] - c[0] + paddingLeft, tv[1] - c[1] + paddingTop)
}

internal fun EdgeLine.toRowRect(origin: Point, contentWidth: Int): Rect =
    Rect(origin.x, origin.y + top, origin.x + contentWidth, origin.y + bottom)


internal fun Rect.withHandleKnob(visualExtentPx: Int, isStart: Boolean): Rect =
    if (isStart) {
        Rect(left, min(top, bottom - visualExtentPx), right, bottom)
    } else {
        Rect(left, top, right, max(bottom, top + visualExtentPx))
    }
