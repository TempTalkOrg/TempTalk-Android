package com.difft.android.chat.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.application
import com.difft.android.base.utils.dp

class TooltipBackgroundDrawable : Drawable() {
    private val paint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(application, com.difft.android.base.R.color.bg_tooltip)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val triangleSize = 8.dp
    private val cornerRadius = 4.dp

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()

        path.reset()

        // 绘制圆角矩形
        path.addRoundRect(0f, 0f, width, height - triangleSize, cornerRadius.toFloat(), cornerRadius.toFloat(), Path.Direction.CW)

        // 绘制三角形
        path.moveTo(width / 2 - triangleSize, height - triangleSize)
        path.lineTo(width / 2, height)
        path.lineTo(width / 2 + triangleSize, height - triangleSize)
        path.close()

        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
} 