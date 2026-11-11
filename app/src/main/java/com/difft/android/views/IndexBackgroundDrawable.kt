package com.difft.android.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.ViewUtil

class IndexBackgroundDrawable(
    context: Context,
    private val view: View
) : Drawable() {

    private val topPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bg1)
        isAntiAlias = true
    }

    private val bottomPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bg2)
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val navigationBarHeight = ViewUtil.getNavigationBarHeight(view)

        canvas.drawRect(
            0f,
            0f,
            bounds.width().toFloat(),
            (bounds.height() - navigationBarHeight).toFloat(),
            topPaint
        )

        canvas.drawRect(
            0f,
            (bounds.height() - navigationBarHeight).toFloat(),
            bounds.width().toFloat(),
            bounds.height().toFloat(),
            bottomPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        topPaint.alpha = alpha
        bottomPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        topPaint.colorFilter = colorFilter
        bottomPaint.colorFilter = colorFilter
    }

    override fun getAlpha(): Int {
        return topPaint.alpha
    }

    override fun isStateful(): Boolean {
        return false
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }
}