package com.difft.android.chat.ui.messageaction

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.chat.R

/**
 * A transparent overlay view that draws text selection highlight.
 * 
 * This view should be positioned to exactly cover the target TextView.
 * It draws the selection highlight based on the text layout and selection range.
 */
class SelectionHighlightView(
    context: Context
) : View(context) {
    
    private var targetTextView: TextView? = null
    private var selectionStart = 0
    private var selectionEnd = 0
    
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Use selection_highlight (t.info with 30% opacity)
        color = ContextCompat.getColor(context, R.color.selection_highlight)
    }
    
    private val highlightPath = Path()
    private val lineRect = RectF()
    private val cornerRadius: Float
    
    init {
        // Make this view transparent and non-interactive
        setBackgroundColor(0x00000000)
        isClickable = false
        isFocusable = false
        
        cornerRadius = 2f * context.resources.displayMetrics.density
    }
    
    /**
     * Bind this highlight view to a TextView.
     */
    fun bindToTextView(textView: TextView) {
        targetTextView = textView
    }
    
    /**
     * Set the selection range.
     */
    fun setSelection(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
        invalidate()
    }
    
    /**
     * Clear the selection.
     */
    fun clearSelection() {
        selectionStart = 0
        selectionEnd = 0
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val textView = targetTextView ?: return
        val layout = textView.layout ?: return
        
        if (selectionStart >= selectionEnd) return
        
        highlightPath.reset()
        
        val startLine = layout.getLineForOffset(selectionStart)
        val endLine = layout.getLineForOffset(selectionEnd)
        
        // Get padding offset
        val paddingLeft = textView.paddingLeft.toFloat()
        val paddingTop = textView.paddingTop.toFloat()
        
        if (startLine == endLine) {
            // Single line selection
            val left = layout.getPrimaryHorizontal(selectionStart) + paddingLeft
            val right = layout.getPrimaryHorizontal(selectionEnd) + paddingLeft
            val top = layout.getLineTop(startLine).toFloat() + paddingTop
            val bottom = layout.getLineBottom(startLine).toFloat() + paddingTop
            
            lineRect.set(left, top, right, bottom)
            highlightPath.addRoundRect(lineRect, cornerRadius, cornerRadius, Path.Direction.CW)
        } else {
            // Multi-line selection
            for (line in startLine..endLine) {
                val lineStart: Float
                val lineEnd: Float
                
                when (line) {
                    startLine -> {
                        // First line: from selection start to line end
                        lineStart = layout.getPrimaryHorizontal(selectionStart)
                        lineEnd = layout.getLineRight(line)
                    }
                    endLine -> {
                        // Last line: from line start to selection end
                        lineStart = layout.getLineLeft(line)
                        lineEnd = layout.getPrimaryHorizontal(selectionEnd)
                    }
                    else -> {
                        // Middle lines: full line
                        lineStart = layout.getLineLeft(line)
                        lineEnd = layout.getLineRight(line)
                    }
                }
                
                val top = layout.getLineTop(line).toFloat() + paddingTop
                val bottom = layout.getLineBottom(line).toFloat() + paddingTop
                
                lineRect.set(
                    lineStart + paddingLeft,
                    top,
                    lineEnd + paddingLeft,
                    bottom
                )
                highlightPath.addRoundRect(lineRect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
        }
        
        canvas.drawPath(highlightPath, highlightPaint)
    }
}
