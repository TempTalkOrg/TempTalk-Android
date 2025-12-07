package com.difft.android.base.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.utils.dp
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
    private val dotSize = 16.dp // 固定16dp
    private val outerCircleSize = 32.dp // 外圈直径32dp
    private val pathWidth = 6.dp // 固定6dp
    private var normalStateColor = Color.GRAY
    private var disabledStateColor = Color.GRAY
    private var selectedStateColor = Color.BLUE
    private var outerCircleColor = Color.LTGRAY
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
    private var isTouchActive = false // 标记触摸是否处于激活状态
    private var currentX = 0f
    private var currentY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val touchRadius = 48.dp / 2f // 固定触摸半径，Android推荐的最小触摸目标（24dp）
    private val initialTouchRadius = 72.dp.toFloat() // 初始触摸半径（72dp），配合路径检测机制

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
        pathPaint.strokeWidth = pathWidth.toFloat()
    }

    private fun setupDefaultColors() {
        // 未选中的点颜色为icon色
        normalStateColor = ContextCompat.getColor(context, R.color.t_third)
        // 禁用状态的点颜色为bg_disable
        disabledStateColor = ContextCompat.getColor(context, R.color.bg_disable)
        // 选中时的点颜色为t_primary
        selectedStateColor = ContextCompat.getColor(context, R.color.t_primary)
        // 外圈颜色为bg_disable
        outerCircleColor = ContextCompat.getColor(context, R.color.bg_disable)
        correctStateColor = ContextCompat.getColor(context, R.color.t_third)
        wrongStateColor = ContextCompat.getColor(context, R.color.t_third)
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
                280.dp
            }
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> heightSize
            else -> {
                // Default size if no constraints
                280.dp
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

        // 留出外圈的半径空间，确保边缘的点选中时外圈不被裁减
        val outerRadius = outerCircleSize.toFloat() / 2
        val availableWidth = viewWidth - 2 * outerRadius
        val availableHeight = viewHeight - 2 * outerRadius

        // 计算点之间的间距
        val horizontalSpacing = if (dotCount > 1) availableWidth / (dotCount - 1) else 0f
        val verticalSpacing = if (dotCount > 1) availableHeight / (dotCount - 1) else 0f

        dots.forEachIndexed { index, dot ->
            val row = index / dotCount
            val col = index % dotCount
            dot.centerX = outerRadius + col * horizontalSpacing
            dot.centerY = outerRadius + row * verticalSpacing
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
        if (selectedDots.size < 2 && !isPatternDrawingStarted) return

        // 线条颜色固定为t_third
        pathPaint.color = ContextCompat.getColor(context, R.color.t_third)

        val outerRadius = outerCircleSize.toFloat() / 2

        // Draw lines between selected dots
        for (i in 0 until selectedDots.size - 1) {
            val fromDot = selectedDots[i]
            val toDot = selectedDots[i + 1]

            // 计算从外圈边缘到外圈边缘的连线
            val (startX, startY) = getEdgePoint(fromDot.centerX, fromDot.centerY, toDot.centerX, toDot.centerY, outerRadius)
            val (endX, endY) = getEdgePoint(toDot.centerX, toDot.centerY, fromDot.centerX, fromDot.centerY, outerRadius)

            canvas.drawLine(startX, startY, endX, endY, pathPaint)
        }

        // Draw line to current touch position if pattern is being drawn
        if (isPatternDrawingStarted && selectedDots.isNotEmpty()) {
            val lastDot = selectedDots.last()
            val (startX, startY) = getEdgePoint(lastDot.centerX, lastDot.centerY, currentX, currentY, outerRadius)
            canvas.drawLine(startX, startY, currentX, currentY, pathPaint)
        }
    }

    /**
     * 计算从点(centerX, centerY)沿着指向(targetX, targetY)方向，距离为radius的边缘点坐标
     */
    private fun getEdgePoint(centerX: Float, centerY: Float, targetX: Float, targetY: Float, radius: Float): Pair<Float, Float> {
        val dx = targetX - centerX
        val dy = targetY - centerY
        val distance = sqrt(dx * dx + dy * dy)

        if (distance == 0f) {
            return Pair(centerX, centerY)
        }

        // 归一化方向向量，然后乘以半径
        val edgeX = centerX + (dx / distance) * radius
        val edgeY = centerY + (dy / distance) * radius

        return Pair(edgeX, edgeY)
    }

    private fun drawDots(canvas: Canvas) {
        dots.forEach { dot ->
            if (dot.isSelected) {
                // 选中的点：绘制外圈 + 内圈
                // 绘制外圈（32dp直径，bg_disable颜色）
                dotPaint.color = outerCircleColor
                canvas.drawCircle(dot.centerX, dot.centerY, outerCircleSize.toFloat() / 2, dotPaint)

                // 绘制内圈（16dp直径，t_primary颜色）
                dotPaint.color = selectedStateColor
                canvas.drawCircle(dot.centerX, dot.centerY, dotSize.toFloat() / 2, dotPaint)
            } else {
                // 未选中的点：根据是否禁用显示不同颜色
                dotPaint.color = if (isInputEnabled) normalStateColor else disabledStateColor
                canvas.drawCircle(dot.centerX, dot.centerY, dotSize.toFloat() / 2, dotPaint)
            }
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
        // 总是激活触摸追踪，提高响应性
        isTouchActive = true
        lastX = currentX
        lastY = currentY

        // 使用较大的初始触摸半径
        val dot = getDotAt(currentX, currentY, initialTouchRadius)
        if (dot != null) {
            isPatternDrawingStarted = true
            selectDot(dot)
            notifyPatternStarted()
            // 立即重绘以提供视觉反馈
            invalidate()
        }
    }

    private fun handleTouchMove() {
        if (!isTouchActive) return

        if (!isPatternDrawingStarted) {
            // 如果还没开始绘制，先检测当前位置
            var dot = getDotAt(currentX, currentY, initialTouchRadius)

            // 如果当前位置没有点，检查从起点滑动过来的路径上是否有点（只检查起点附近）
            if (dot == null) {
                dot = findDotNearStartPath(lastX, lastY, currentX, currentY)
            }

            if (dot != null) {
                isPatternDrawingStarted = true
                selectDot(dot)
                notifyPatternStarted()
            }
        } else {
            // 已经开始绘制，只检测当前位置的点，使用正常半径
            val dot = getDotAt(currentX, currentY, touchRadius)
            if (dot != null && !dot.isSelected) {
                selectDot(dot)
                notifyPatternProgress()
            }
        }

        invalidate()
    }

    /**
     * 查找从起点附近滑动路径上的点
     * 只检测距离起点很近的点，避免长距离滑动时误选
     */
    private fun findDotNearStartPath(startX: Float, startY: Float, currentX: Float, currentY: Float): Dot? {
        // 只在短距离内检测（约100dp），避免误选远处的点
        val moveDistance = sqrt((currentX - startX).pow(2) + (currentY - startY).pow(2))
        if (moveDistance > 100.dp) return null

        // 找出距离起点最近且在路径上的点
        return dots.find { dot ->
            // 点必须距离起点足够近
            val distanceFromStart = sqrt((dot.centerX - startX).pow(2) + (dot.centerY - startY).pow(2))
            if (distanceFromStart > initialTouchRadius) return@find false

            // 点到路径的距离要小于半径
            val distanceToPath = pointToLineDistance(dot.centerX, dot.centerY, startX, startY, currentX, currentY)
            distanceToPath <= initialTouchRadius / 2
        }
    }

    /**
     * 计算点到线段的距离
     */
    private fun pointToLineDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0f) {
            return sqrt((px - x1).pow(2) + (py - y1).pow(2))
        }

        var t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared
        t = maxOf(0f, minOf(1f, t))

        val projX = x1 + t * dx
        val projY = y1 + t * dy

        return sqrt((px - projX).pow(2) + (py - projY).pow(2))
    }

    private fun handleTouchEnd() {
        if (isPatternDrawingStarted) {
            isPatternDrawingStarted = false
            notifyPatternComplete()
        }
        // 重置触摸激活状态
        isTouchActive = false
    }

    private fun getDotAt(x: Float, y: Float, radius: Float = touchRadius): Dot? {
        // 使用指定的触摸半径进行检测
        return dots.find { dot ->
            val distance = sqrt((x - dot.centerX).pow(2) + (y - dot.centerY).pow(2))
            distance <= radius
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
        isTouchActive = false
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
        invalidate() // 重绘以更新圆点颜色
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
