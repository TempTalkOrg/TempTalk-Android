package com.difft.android.base.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import kotlin.math.*

class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Pattern view modes
    enum class PatternViewMode {
        CORRECT, WRONG
    }

    // Dot state
    data class Dot(
        val id: Int,
        val row: Int,
        val column: Int,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var isSelected: Boolean = false
    )

    // Listener interface
    interface PatternLockViewListener {
        fun onStarted()
        fun onProgress(progressPattern: MutableList<Dot>)
        fun onComplete(pattern: MutableList<Dot>)
        fun onCleared()
    }

    // Properties
    private var dotCount = 3
    private val dotSize = 20f * context.resources.displayMetrics.density // 固定20dp
    private val pathWidth = 10f * context.resources.displayMetrics.density // 固定14dp
    private var normalStateColor = Color.GRAY
    private var correctStateColor = Color.BLUE
    private var wrongStateColor = Color.RED
    private var isInStealthMode = false
    private var isInputEnabled = true
    private var isTactileFeedbackEnabled = true
    private var dotAnimationDuration = 200L
    private var pathEndAnimationDuration = 100L

    // Internal state
    private var currentViewMode = PatternViewMode.CORRECT
    private val dots = mutableListOf<Dot>()
    private val selectedDots = mutableListOf<Dot>()
    private val listeners = mutableListOf<PatternLockViewListener>()
    private var isPatternDrawingStarted = false
    private var currentX = 0f
    private var currentY = 0f

    // Paint objects
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setupDefaultColors()
        initializeDots()
        pathPaint.strokeWidth = pathWidth
    }

    private fun setupDefaultColors() {
        // 点的颜色固定为icon色，不变化
        normalStateColor = ContextCompat.getColor(context, R.color.icon)
        correctStateColor = ContextCompat.getColor(context, R.color.icon)
        wrongStateColor = ContextCompat.getColor(context, R.color.icon)
    }

    private fun initializeDots() {
        dots.clear()
        for (row in 0 until dotCount) {
            for (col in 0 until dotCount) {
                val id = row * dotCount + col
                dots.add(Dot(id, row, col))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> widthSize
            else -> {
                // Default size if no constraints
                (280 * context.resources.displayMetrics.density).toInt()
            }
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> heightSize
            else -> {
                // Default size if no constraints
                (280 * context.resources.displayMetrics.density).toInt()
            }
        }

        // Make it square by using the smaller dimension
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDotPositions(w, h)
    }

    private fun calculateDotPositions(width: Int, height: Int) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        // 留出点的半径空间，确保边缘的点不被裁减
        val dotRadius = dotSize / 2
        val availableWidth = viewWidth - 2 * dotRadius
        val availableHeight = viewHeight - 2 * dotRadius
        
        // 计算点之间的间距
        val horizontalSpacing = if (dotCount > 1) availableWidth / (dotCount - 1) else 0f
        val verticalSpacing = if (dotCount > 1) availableHeight / (dotCount - 1) else 0f

        dots.forEachIndexed { index, dot ->
            val row = index / dotCount
            val col = index % dotCount
            dot.centerX = dotRadius + col * horizontalSpacing
            dot.centerY = dotRadius + row * verticalSpacing
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isInStealthMode) {
            drawPath(canvas)
        }
        drawDots(canvas)
    }

    private fun drawPath(canvas: Canvas) {
        if (selectedDots.size < 2) return

        // 线条颜色固定为t_third
        pathPaint.color = ContextCompat.getColor(context, R.color.t_third)

        // Draw lines between selected dots
        for (i in 0 until selectedDots.size - 1) {
            val fromDot = selectedDots[i]
            val toDot = selectedDots[i + 1]
            canvas.drawLine(
                fromDot.centerX, fromDot.centerY,
                toDot.centerX, toDot.centerY,
                pathPaint
            )
        }

        // Draw line to current touch position if pattern is being drawn
        if (isPatternDrawingStarted && selectedDots.isNotEmpty()) {
            val lastDot = selectedDots.last()
            canvas.drawLine(
                lastDot.centerX, lastDot.centerY,
                currentX, currentY,
                pathPaint
            )
        }
    }

    private fun drawDots(canvas: Canvas) {
        dots.forEach { dot ->
            // 所有点的大小和颜色都固定不变
            dotPaint.color = normalStateColor
            canvas.drawCircle(dot.centerX, dot.centerY, dotSize / 2, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputEnabled) return false

        currentX = event.x
        currentY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleTouchStart()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                handleTouchMove()
                return true
            }

            MotionEvent.ACTION_UP -> {
                handleTouchEnd()
                return true
            }
        }
        return false
    }

    private fun handleTouchStart() {
        val dot = getDotAt(currentX, currentY)
        if (dot != null) {
            isPatternDrawingStarted = true
            selectDot(dot)
            notifyPatternStarted()
            // 立即重绘以提供视觉反馈
            invalidate()
        }
    }

    private fun handleTouchMove() {
        if (!isPatternDrawingStarted) {
            // 如果还没开始绘制，尝试开始
            val dot = getDotAt(currentX, currentY)
            if (dot != null) {
                isPatternDrawingStarted = true
                selectDot(dot)
                notifyPatternStarted()
            }
        } else {
            // 已经开始绘制，检查是否选中新的点
            val dot = getDotAt(currentX, currentY)
            if (dot != null && !dot.isSelected) {
                selectDot(dot)
                notifyPatternProgress()
            }
        }
        invalidate()
    }

    private fun handleTouchEnd() {
        if (isPatternDrawingStarted) {
            isPatternDrawingStarted = false
            notifyPatternComplete()
        }
    }

    private fun getDotAt(x: Float, y: Float): Dot? {
        // 增加触摸区域，使用48dp的触摸半径（Android推荐的最小触摸目标大小）
        val touchRadius = 48f * context.resources.displayMetrics.density / 2
        return dots.find { dot ->
            val distance = sqrt((x - dot.centerX).pow(2) + (y - dot.centerY).pow(2))
            distance <= touchRadius
        }
    }

    private fun selectDot(dot: Dot) {
        if (!dot.isSelected) {
            dot.isSelected = true
            selectedDots.add(dot)

            if (isTactileFeedbackEnabled) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }

            invalidate()
        }
    }

    private fun notifyPatternStarted() {
        listeners.forEach { it.onStarted() }
    }

    private fun notifyPatternProgress() {
        listeners.forEach { it.onProgress(selectedDots.toMutableList()) }
    }

    private fun notifyPatternComplete() {
        listeners.forEach { it.onComplete(selectedDots.toMutableList()) }
    }

    private fun notifyPatternCleared() {
        listeners.forEach { it.onCleared() }
    }

    // Public API methods
    fun addPatternLockListener(listener: PatternLockViewListener) {
        listeners.add(listener)
    }

    fun removePatternLockListener(listener: PatternLockViewListener) {
        listeners.remove(listener)
    }

    fun clearPattern() {
        dots.forEach { it.isSelected = false }
        selectedDots.clear()
        isPatternDrawingStarted = false
        currentViewMode = PatternViewMode.CORRECT
        invalidate()
        notifyPatternCleared()
    }

    fun setViewMode(mode: PatternViewMode) {
        currentViewMode = mode
        invalidate()
    }

    fun setInStealthMode(inStealthMode: Boolean) {
        isInStealthMode = inStealthMode
        invalidate()
    }

    fun setInputEnabled(inputEnabled: Boolean) {
        isInputEnabled = inputEnabled
    }

    fun setTactileFeedbackEnabled(tactileFeedbackEnabled: Boolean) {
        isTactileFeedbackEnabled = tactileFeedbackEnabled
    }

    // Setters for customization
    fun setDotCount(count: Int) {
        dotCount = count
        initializeDots()
        requestLayout()
    }

    // 移除了点大小和路径宽度的setter方法，因为现在是固定值

    // 移除了颜色setter方法，因为现在颜色是固定的

    fun setDotAnimationDuration(duration: Long) {
        dotAnimationDuration = duration
    }

    fun setPathEndAnimationDuration(duration: Long) {
        pathEndAnimationDuration = duration
    }
}
