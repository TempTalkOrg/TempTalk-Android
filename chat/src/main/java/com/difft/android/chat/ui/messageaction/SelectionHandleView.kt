package com.difft.android.chat.ui.messageaction

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R as BaseR

/**
 * A draggable selection handle view.
 * 
 * The handle consists of a circle with a thin line pointing to the text.
 * - Start handle: circle center with line pointing DOWN (below the circle)
 * - End handle: circle center with line pointing UP (above the circle)
 * 
 * @param isStart true for start handle (line below), false for end handle (line above)
 */
@SuppressLint("ViewConstructor")
class SelectionHandleView(
    context: Context,
    private val isStart: Boolean
) : View(context) {
    
    /**
     * Callback when handle is dragged.
     * @param deltaX horizontal movement since last event
     * @param deltaY vertical movement since last event
     */
    var onDrag: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    
    /**
     * Callback when drag starts
     */
    var onDragStart: (() -> Unit)? = null
    
    /**
     * Callback when drag ends
     */
    var onDragEnd: (() -> Unit)? = null
    
    // Dimensions
    private val circleRadius: Float  // 4dp (diameter 8dp)
    private val lineWidth: Float     // 3dp
    private var lineHeight: Float    // Default ~20dp, can be updated to match line height
    
    // Touch padding - extra touch area around the visual handle
    private val touchPadding: Float  // 16dp on each side for easier grabbing
    
    // Visual dimensions (what gets drawn)
    private val visualWidth: Float
    private var visualHeight: Float
    
    // Total view dimensions (includes touch padding)
    private var totalWidth: Float
    var totalHeight: Float
        private set
    
    // The X offset from this view's left edge to the point where it connects to text
    val pivotOffsetX: Float
        get() = touchPadding + circleRadius  // Center of the circle (accounting for padding)
    
    // The Y offset from this view's top edge to the connection point
    val pivotOffsetY: Float
        get() = if (isStart) {
            // Start handle: circle at top, line points down - connection at bottom
            totalHeight - touchPadding
        } else {
            // End handle: line points up, circle at bottom - connection at top
            touchPadding
        }
    
    // Paint - use t.info color from base module
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, BaseR.color.t_info)
    }
    
    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    
    private val density = context.resources.displayMetrics.density
    
    init {
        // Handle dimensions in dp
        circleRadius = 4f * density   // 8dp diameter
        lineWidth = 3f * density      // 3dp line width
        lineHeight = 20f * density    // Default, will be updated
        touchPadding = 16f * density  // 16dp touch padding for easier grabbing
        
        // Visual size (circle + line)
        visualWidth = circleRadius * 2
        visualHeight = circleRadius * 2 + lineHeight
        
        // Total view size (includes touch padding)
        totalWidth = visualWidth + touchPadding * 2
        totalHeight = visualHeight + touchPadding * 2
    }
    
    /**
     * Update the line height to match text line height
     */
    fun setLineHeight(height: Float) {
        lineHeight = height.coerceAtLeast(12f * density)  // Minimum 12dp
        visualHeight = circleRadius * 2 + lineHeight
        totalHeight = visualHeight + touchPadding * 2
        requestLayout()
        invalidate()
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalWidth.toInt(), totalHeight.toInt())
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw centered within the touch padding area
        val circleX = touchPadding + circleRadius  // Center X (accounting for padding)
        
        if (isStart) {
            // Start handle: circle at top, line below pointing down to text
            // Circle at top
            val circleY = touchPadding + circleRadius
            canvas.drawCircle(circleX, circleY, circleRadius, paint)
            // Line from circle to bottom of visual area
            canvas.drawRect(
                circleX - lineWidth / 2,
                touchPadding + circleRadius * 2,
                circleX + lineWidth / 2,
                touchPadding + visualHeight,
                paint
            )
        } else {
            // End handle: line above pointing up to text, circle at bottom
            // Line from top of visual area to circle
            canvas.drawRect(
                circleX - lineWidth / 2,
                touchPadding,
                circleX + lineWidth / 2,
                touchPadding + lineHeight,
                paint
            )
            // Circle at bottom
            val circleY = touchPadding + lineHeight + circleRadius
            canvas.drawCircle(circleX, circleY, circleRadius, paint)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = true
                onDragStart?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    onDrag?.invoke(deltaX, deltaY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd?.invoke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
