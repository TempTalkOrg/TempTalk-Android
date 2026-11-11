package com.difft.android.base.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
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

    
    enum class PatternViewMode {
        CORRECT, WRONG
    }

    
    data class Dot(
        val id: Int,
        val row: Int,
        val column: Int,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var isSelected: Boolean = false
    )

    
    interface PatternLockViewListener {
        fun onStarted()
        fun onProgress(progressPattern: MutableList<Dot>)
        fun onComplete(pattern: MutableList<Dot>)
        fun onCleared()
    }

    
    private var dotCount = 3
    
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private val dotSize = 16.dp
    private val outerCircleSize = 32.dp
    
    private val pathWidth = 14.dp
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

    
    private var currentViewMode = PatternViewMode.CORRECT
    private val dots = mutableListOf<Dot>()
    private val selectedDots = mutableListOf<Dot>()
    private val listeners = mutableListOf<PatternLockViewListener>()
    private var isPatternDrawingStarted = false
    private var isTouchActive = false
    
    private var isSelectionHidden = false
    private val releaseFade = PatternReleaseFade()
    private var currentX = 0f
    private var currentY = 0f
    private val touchRadius = 48.dp / 2f 
    private val initialTouchRadius = 72.dp.toFloat() 
    private val startSlideMaxDistance = 100.dp.toFloat()

    private var grid = PatternGrid(dotCount)
    private var selector = newSelector()
    private var legacyTracker = newLegacyTracker()
    private val trail = PatternTrailState()
    private val delayedClear = Runnable { clearPattern() }

    
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
        normalStateColor = ContextCompat.getColor(context, R.color.t_third)
        
        disabledStateColor = ContextCompat.getColor(context, R.color.bg4)
        
        selectedStateColor = ContextCompat.getColor(context, R.color.t_primary)
        
        outerCircleColor = ContextCompat.getColor(context, R.color.bg4)
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
        selectedDots.clear()
        trail.reset()
        grid = PatternGrid(dotCount)
        selector = newSelector()
        legacyTracker = newLegacyTracker()
    }

    private fun newSelector() = PatternSelector(grid, touchRadius, initialTouchRadius, startSlideMaxDistance)

    private fun newLegacyTracker() = LegacyPatternTracker(grid, touchRadius, initialTouchRadius, startSlideMaxDistance)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val availableWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> MAX_SIZE_DP.dp 
        }

        val availableHeight = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
            else -> availableWidth
        }

        
        val size = minOf(availableWidth, availableHeight, MAX_SIZE_DP.dp)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDotPositions(w, h)
    }

    private fun calculateDotPositions(width: Int, height: Int) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        
        val outerRadius = outerCircleSize.toFloat() / 2
        val availableWidth = viewWidth - 2 * outerRadius
        val availableHeight = viewHeight - 2 * outerRadius

        val horizontalSpacing = if (dotCount > 1) availableWidth / (dotCount - 1) else 0f
        val verticalSpacing = if (dotCount > 1) availableHeight / (dotCount - 1) else 0f

        dots.forEachIndexed { index, dot ->
            val row = index / dotCount
            val col = index % dotCount
            dot.centerX = outerRadius + col * horizontalSpacing
            dot.centerY = outerRadius + row * verticalSpacing
            grid.centerX[index] = dot.centerX
            grid.centerY[index] = dot.centerY
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        if (!isInStealthMode) {
            drawPath(canvas, now)
        }
        drawDots(canvas, now)
        drawReleaseFade(canvas, now)
        if (!isSelectionHidden && trail.isAnimating(now, isInStealthMode)) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawReleaseFade(canvas: Canvas, now: Long) {
        val factor = releaseFade.factor(now)
        if (factor <= 0f) return
        releaseFade.dots.forEach { d -> drawLitDot(canvas, d[0], d[1], d[2] * factor) }
        postInvalidateOnAnimation()
    }

    
    
    private fun captureReleaseFade(now: Long) {
        releaseFade.clear()
        dots.forEach { dot ->
            val alpha = dotLitAlpha(dot, now)
            if (alpha > 0f) releaseFade.dots.add(floatArrayOf(dot.centerX, dot.centerY, alpha))
        }
        releaseFade.start(now)
    }

    private fun drawPath(canvas: Canvas, now: Long) {
        if (selectedDots.size < 2 && !isPatternDrawingStarted) return

        val pathColor = ContextCompat.getColor(context, R.color.t_secondary)
        val outerRadius = outerCircleSize.toFloat() / 2

        forEachVisibleSegment(now) { x1, y1, x2, y2, alpha ->
            pathPaint.color = withAlpha(pathColor, alpha)
            canvas.drawLine(x1, y1, x2, y2, pathPaint)
        }

        
        if (isPatternDrawingStarted && selectedDots.isNotEmpty()) {
            val lastDot = selectedDots.last()
            val (startX, startY) = PatternGrid.edgePoint(lastDot.centerX, lastDot.centerY, currentX, currentY, outerRadius)
            pathPaint.color = pathColor
            canvas.drawLine(startX, startY, currentX, currentY, pathPaint)
        }
    }

    
    private inline fun forEachVisibleSegment(now: Long, block: (Float, Float, Float, Float, Float) -> Unit) {
        val outerRadius = outerCircleSize.toFloat() / 2
        for (i in 0 until selectedDots.size - 1) {
            val alpha = pathAlpha(i, now)
            if (alpha <= 0f) continue
            val fromDot = selectedDots[i]
            val toDot = selectedDots[i + 1]
            val (startX, startY) = PatternGrid.edgePoint(fromDot.centerX, fromDot.centerY, toDot.centerX, toDot.centerY, outerRadius)
            val (endX, endY) = PatternGrid.edgePoint(toDot.centerX, toDot.centerY, fromDot.centerX, fromDot.centerY, outerRadius)
            val wipe = trail.wipeProgress(i, now)
            block(startX + (endX - startX) * wipe, startY + (endY - startY) * wipe, endX, endY, alpha)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    
    private fun pathAlpha(index: Int, now: Long): Float = when {
        isSelectionHidden -> 0f
        else -> trail.segmentAlpha(index, now)
    }

    
    private fun dotLitAlpha(dot: Dot, now: Long): Float {
        val index = selectedDots.indexOf(dot)
        return when {
            index < 0 -> 0f
            isSelectionHidden -> 0f
            isInStealthMode -> trail.pulseAlpha(index, now)
            
            else -> 1f
        }
    }

    private fun drawDots(canvas: Canvas, now: Long) {
        dots.forEach { dot ->
            val litAlpha = dotLitAlpha(dot, now)
            if (litAlpha < 1f) {
                dotPaint.color = if (isInputEnabled) normalStateColor else disabledStateColor
                canvas.drawCircle(dot.centerX, dot.centerY, dotSize.toFloat() / 2, dotPaint)
            }
            if (litAlpha > 0f) drawLitDot(canvas, dot.centerX, dot.centerY, litAlpha)
        }
    }

    
    private fun drawLitDot(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        dotPaint.color = withAlpha(outerCircleColor, alpha)
        canvas.drawCircle(x, y, outerCircleSize.toFloat() / 2, dotPaint)
        dotPaint.color = withAlpha(selectedStateColor, alpha)
        canvas.drawCircle(x, y, dotSize.toFloat() / 2, dotPaint)
    }

    
    @SuppressLint("ClickableViewAccessibility")
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
                handleTouchMove(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                handleTouchEnd()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handleTouchCancel()
                return true
            }
        }
        return false
    }

    private fun handleTouchStart() {
        
        removeCallbacks(delayedClear)
        releaseFade.clear()
        if (selectedDots.isNotEmpty() || isSelectionHidden) clearPattern()

        isTouchActive = true
        legacyTracker.down(currentX, currentY)
        if (applySelection(selector.down(currentX, currentY))) {
            isPatternDrawingStarted = true
            notifyPatternStarted()
            invalidate()
        }
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (!isTouchActive) return

        legacyTracker.move(currentX, currentY)

        
        val countBefore = selectedDots.size
        for (i in 0 until event.historySize) {
            applySelection(selector.move(event.getHistoricalX(i), event.getHistoricalY(i)))
        }
        applySelection(selector.move(currentX, currentY))

        if (selectedDots.size > countBefore) {
            if (!isPatternDrawingStarted) {
                isPatternDrawingStarted = true
                notifyPatternStarted()
            }
            
            if (selectedDots.size > 1) notifyPatternProgress()
        }

        invalidate()
    }

    private fun handleTouchEnd() {
        if (isPatternDrawingStarted) {
            isPatternDrawingStarted = false
            
            captureReleaseFade(SystemClock.uptimeMillis())
            isSelectionHidden = true
            notifyPatternComplete()
            invalidate()
        }
        isTouchActive = false
    }

    
    private fun handleTouchCancel() {
        clearPattern()
    }

    
    private fun applySelection(ids: List<Int>): Boolean {
        ids.forEach { selectDot(dots[it]) }
        return ids.isNotEmpty()
    }

    private fun selectDot(dot: Dot) {
        if (!dot.isSelected) {
            dot.isSelected = true
            selectedDots.add(dot)
            trail.onDotAdded(SystemClock.uptimeMillis())

            if (isTactileFeedbackEnabled) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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

    
    fun addPatternLockListener(listener: PatternLockViewListener) {
        listeners.add(listener)
    }

    fun removePatternLockListener(listener: PatternLockViewListener) {
        listeners.remove(listener)
    }

    



    val legacyPattern: List<Dot> get() = legacyTracker.selected.map { dots[it] }

    
    fun clearPatternDelayed(delayMs: Long) {
        removeCallbacks(delayedClear)
        postDelayed(delayedClear, delayMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(delayedClear)
        super.onDetachedFromWindow()
    }

    fun clearPattern() {
        removeCallbacks(delayedClear)
        dots.forEach { it.isSelected = false }
        selectedDots.clear()
        selector.reset()
        legacyTracker.reset()
        trail.reset()
        isSelectionHidden = false
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
        invalidate()
    }

    fun setTactileFeedbackEnabled(tactileFeedbackEnabled: Boolean) {
        isTactileFeedbackEnabled = tactileFeedbackEnabled
    }

    
    fun setDotCount(count: Int) {
        dotCount = count
        initializeDots()
        requestLayout()
    }



    fun setDotAnimationDuration(duration: Long) {
        dotAnimationDuration = duration
    }

    fun setPathEndAnimationDuration(duration: Long) {
        pathEndAnimationDuration = duration
    }

    companion object {
        
        
        const val MAX_SIZE_DP = 260
    }
}
