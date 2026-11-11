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










@SuppressLint("ViewConstructor")
class SelectionHandleView(
    context: Context,
    private val isStart: Boolean
) : View(context) {
    
    




    var onDrag: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    
    


    var onDragStart: (() -> Unit)? = null
    
    


    var onDragEnd: (() -> Unit)? = null
    
    
    private val circleRadius: Float  
    private val lineWidth: Float     
    private var lineHeight: Float    
    
    
    private val touchPadding: Float  
    
    
    private val visualWidth: Float
    private var visualHeight: Float

    
    val visualExtentPx: Float
        get() = visualHeight

    
    val visualTopInset: Float
        get() = touchPadding

    
    var totalWidth: Float
        private set
    var totalHeight: Float
        private set
    
    
    val pivotOffsetX: Float
        get() = touchPadding + circleRadius  
    
    
    val pivotOffsetY: Float
        get() = if (isStart) {
            
            totalHeight - touchPadding
        } else {
            
            touchPadding
        }
    
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, BaseR.color.info)
    }
    
    
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    
    private val density = context.resources.displayMetrics.density
    
    init {
        
        circleRadius = 4f * density   
        lineWidth = 3f * density      
        lineHeight = 20f * density    
        touchPadding = 16f * density  
        
        
        visualWidth = circleRadius * 2
        visualHeight = circleRadius * 2 + lineHeight
        
        
        totalWidth = visualWidth + touchPadding * 2
        totalHeight = visualHeight + touchPadding * 2
    }
    
    


    fun setLineHeight(height: Float) {
        lineHeight = height.coerceAtLeast(12f * density)  
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
        
        
        val circleX = touchPadding + circleRadius  
        
        if (isStart) {
            
            
            val circleY = touchPadding + circleRadius
            canvas.drawCircle(circleX, circleY, circleRadius, paint)
            
            canvas.drawRect(
                circleX - lineWidth / 2,
                touchPadding + circleRadius * 2,
                circleX + lineWidth / 2,
                touchPadding + visualHeight,
                paint
            )
        } else {
            
            
            canvas.drawRect(
                circleX - lineWidth / 2,
                touchPadding,
                circleX + lineWidth / 2,
                touchPadding + lineHeight,
                paint
            )
            
            val circleY = touchPadding + lineHeight + circleRadius
            canvas.drawCircle(circleX, circleY, circleRadius, paint)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = true
                onDragStart?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    
                    val index = event.findPointerIndex(activePointerId)
                    if (index < 0) return true
                    val rawX = event.getX(index) + (event.rawX - event.x)
                    val rawY = event.getY(index) + (event.rawY - event.y)
                    val deltaX = rawX - lastTouchX
                    val deltaY = rawY - lastTouchY
                    lastTouchX = rawX
                    lastTouchY = rawY
                    onDrag?.invoke(deltaX, deltaY)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (isDragging) return true
            MotionEvent.ACTION_POINTER_UP -> {
                if (isDragging) {
                    if (event.getPointerId(event.actionIndex) == activePointerId) endDrag()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    endDrag()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endDrag() {
        isDragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        onDragEnd?.invoke()
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
