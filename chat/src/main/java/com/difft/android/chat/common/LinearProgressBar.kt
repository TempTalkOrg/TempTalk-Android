package com.difft.android.chat.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class LinearProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.primary) // 进度条颜色
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT // 背景颜色
        style = Paint.Style.FILL
    }

    private var progress: Float = 0f // 当前进度（0 ~ 100）

    /**
     * 设置进度
     * @param progress 进度值（0~100）
     */
    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 100f)
        invalidate() // 重新绘制
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 绘制背景
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // 绘制进度
        val progressWidth = width * (progress / 100f)
        canvas.drawRect(0f, 0f, progressWidth, height, progressPaint)
    }
}