package com.difft.android.chat.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.difft.android.chat.R

class CircleProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0
    private var maxProgress = 100
    private var strokeWidth = 10f
    private var progressBarColor = Color.BLUE
    private var circleBackgroundColor = Color.GRAY

    private val paint = Paint()

    init {
        // 获取自定义属性
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.CircleProgressBar)
        progressBarColor = typedArray.getColor(R.styleable.CircleProgressBar_progressBarColor, Color.BLUE)
        circleBackgroundColor = typedArray.getColor(R.styleable.CircleProgressBar_circleBackgroundColor, Color.GRAY)
        strokeWidth = typedArray.getDimension(R.styleable.CircleProgressBar_strokeWidth, 10f)
        typedArray.recycle()

        // 初始化画笔
        paint.isAntiAlias = true
        paint.strokeWidth = strokeWidth
        paint.style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = Math.min(centerX, centerY) - strokeWidth / 2

        // 绘制底色
        paint.color = circleBackgroundColor
        canvas.drawCircle(centerX, centerY, radius, paint)

        val angle = 360 * progress / maxProgress

        // 绘制进度
        paint.color = progressBarColor
        canvas.drawArc(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
            -90f,
            angle.toFloat(),
            false,
            paint
        )
    }

    fun setProgress(progress: Int) {
        this.progress = progress
        invalidate()
    }

    fun setMaxProgress(maxProgress: Int) {
        this.maxProgress = maxProgress
    }

    fun setProgressBarColor(color: Int) {
        progressBarColor = color
        invalidate()
    }

    fun setStrokeWidth(width: Float) {
        strokeWidth = width
        paint.strokeWidth = strokeWidth
        invalidate()
    }

    fun setCircleBackgroundColor(color: Int) {
        circleBackgroundColor = color
        invalidate()
    }
}
