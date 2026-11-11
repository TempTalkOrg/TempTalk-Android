package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView











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

    





    private fun buildCenteredTail(full: String): String {
        val availableWidth = measuredWidth - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) return ""
        val maxTailWidth = (availableWidth - paint.measureText(ELLIPSIS)) / 2f
        if (maxTailWidth <= 0) return ""
        
        val minStart = full.offsetByCodePoints(0, full.codePointCount(0, full.length) / 2)
        var start = full.length
        while (start > minStart) {
            val prev = full.offsetByCodePoints(start, -1)
            if (paint.measureText(full, prev, full.length) > maxTailWidth) break
            start = prev
        }
        return full.substring(start)
    }

    
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
