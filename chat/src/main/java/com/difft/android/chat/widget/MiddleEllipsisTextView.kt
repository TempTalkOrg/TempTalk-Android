package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView that middle-ellipsizes text overflowing [getMaxLines], keeping the tail
 * (e.g. a file extension) visible. Android's native ellipsize="middle" only works
 * for single-line text.
 *
 * Truncation runs entirely inside [onMeasure], using the view's own layout as the
 * measuring engine — the first drawn frame is already final, so there is no flicker
 * and no RecyclerView-reuse race. If a candidate cannot converge, the XML
 * ellipsize="end" fallback renders instead (never clipped text).
 */
class MiddleEllipsisTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var fullText: String? = null
    private var selfUpdating = false

    private var cachedFull: String? = null
    private var cachedWidth = -1
    private var cachedDisplay: String? = null

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!selfUpdating) {
            fullText = text?.toString()
            cachedFull = null
        }
        super.setText(text, type)
    }

    override fun requestLayout() {
        // Suppress layout requests from internal candidate setText during onMeasure
        if (!selfUpdating) super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val full = fullText ?: return
        if (maxLines <= 0 || maxLines == Int.MAX_VALUE) return

        if (cachedFull == full && cachedWidth == measuredWidth) {
            val display = cachedDisplay ?: return
            if (text?.toString() != display) {
                selfSet(display)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
            return
        }

        // Measure against the full text first (view may still hold an old truncated string)
        if (text?.toString() != full) {
            selfSet(full)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        if (fits(full)) {
            cache(full, full)
            return
        }

        val display = buildMiddleEllipsized(full, widthMeasureSpec, heightMeasureSpec)
        selfSet(display)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        cache(full, display)
    }

    /**
     * Binary-searches the longest head so that "head…tail" fits within maxLines,
     * verifying each candidate with the view's own layout. Falls back to the full
     * text (end-ellipsized by the framework) when even the shortest candidate
     * cannot fit. Cuts at code point boundaries to avoid splitting surrogate pairs.
     *
     * WrongCall: super.onMeasure here is a deliberate self re-measure of each
     * candidate — this helper only runs inside [onMeasure], within one measure pass.
     */
    @SuppressLint("WrongCall")
    private fun buildMiddleEllipsized(full: String, widthSpec: Int, heightSpec: Int): String {
        val tail = buildCenteredTail(full)
        if (tail.isEmpty()) return full
        val totalCp = full.codePointCount(0, full.length)
        val tailCp = tail.codePointCount(0, tail.length)

        var low = 0
        var high = totalCp - tailCp
        var best: String? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = full.substring(0, full.offsetByCodePoints(0, mid)) + ELLIPSIS + tail
            selfSet(candidate)
            super.onMeasure(widthSpec, heightSpec)
            if (fits(candidate)) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best ?: full
    }

    /**
     * Takes code points from the end until the tail fills about half of the line width
     * (minus the ellipsis), so the ellipsis lands near the visual center of the last
     * line — matching the single-line ellipsize="middle" convention. The head side is
     * maximized by the caller's binary search, so the line before the ellipsis stays full.
     */
    private fun buildCenteredTail(full: String): String {
        val availableWidth = measuredWidth - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) return ""
        val maxTailWidth = (availableWidth - paint.measureText(ELLIPSIS)) / 2f
        if (maxTailWidth <= 0) return ""
        // Keep the head non-empty: never take more than half of the code points
        val minStart = full.offsetByCodePoints(0, full.codePointCount(0, full.length) / 2)
        var start = full.length
        while (start > minStart) {
            val prev = full.offsetByCodePoints(start, -1)
            if (paint.measureText(full, prev, full.length) > maxTailWidth) break
            start = prev
        }
        return full.substring(start)
    }

    /** True when the given text (currently set on this view) renders fully within maxLines. */
    private fun fits(current: String): Boolean {
        val layout = layout ?: return true
        if (layout.lineCount > maxLines) return false
        val lastLine = layout.lineCount - 1
        return layout.getEllipsisCount(lastLine) == 0 && layout.getLineEnd(lastLine) >= current.length
    }

    private fun selfSet(t: CharSequence) {
        selfUpdating = true
        try {
            text = t
        } finally {
            selfUpdating = false
        }
    }

    private fun cache(full: String, display: String) {
        cachedFull = full
        cachedWidth = measuredWidth
        cachedDisplay = display
    }

    companion object {
        private const val ELLIPSIS = "…"
    }
}
