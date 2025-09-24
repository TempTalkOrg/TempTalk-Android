package com.difft.android.chat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.chat.R
import org.thoughtcrime.securesms.util.ViewUtil

class ChatBackgroundDrawable(
    context: Context,
    private val view: View,
    private val drawSystemBars: Boolean = false // 新增参数，控制是否绘制系统栏
) : Drawable() {

    private val backgroundDrawable: Drawable = ContextCompat.getDrawable(context, R.drawable.chat_bg_new)!!
    private val matrix = Matrix()

    // 创建画笔用于绘制状态栏和导航栏背景
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.bg1)
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val drawableWidth = backgroundDrawable.intrinsicWidth
        val drawableHeight = backgroundDrawable.intrinsicHeight

        // 使用ViewUtil获取状态栏和导航栏高度
        val statusBarHeight = ViewUtil.getStatusBarHeight(view)
        val navigationBarHeight = ViewUtil.getNavigationBarHeight(view)

        // 根据参数决定是否绘制系统栏背景
        if (drawSystemBars) {
            // 1. 绘制状态栏背景（bg1颜色）
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), statusBarHeight.toFloat(), paint)

            // 2. 绘制导航栏背景（bg1颜色）
            canvas.drawRect(
                0f,
                (bounds.height() - navigationBarHeight).toFloat(),
                bounds.width().toFloat(),
                bounds.height().toFloat(),
                paint
            )
        }

        // 3. 绘制聊天背景图（只在内容区域）
        // 计算缩放比例，保持宽高比
        val scaleX = bounds.width().toFloat() / drawableWidth
        val scaleY = bounds.height().toFloat() / drawableHeight
        val scale = maxOf(scaleX, scaleY)

        // 计算居中偏移
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        val translateX = (bounds.width() - scaledWidth) / 2
        val translateY = (bounds.height() - scaledHeight) / 2

        // 设置变换矩阵
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(translateX, translateY)

        // 保存画布状态
        canvas.save()

        // 根据参数决定是否裁剪系统栏区域
        if (drawSystemBars) {
            // 裁剪掉状态栏和导航栏区域，只在内容区域绘制背景图
            canvas.clipRect(
                0,
                statusBarHeight,
                bounds.width(),
                bounds.height() - navigationBarHeight
            )
        }

        // 绘制聊天背景图
        backgroundDrawable.bounds = Rect(0, 0, drawableWidth, drawableHeight)
        canvas.save()
        canvas.concat(matrix)
        backgroundDrawable.draw(canvas)
        canvas.restore()

        // 恢复画布状态
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        backgroundDrawable.alpha = alpha
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        backgroundDrawable.colorFilter = colorFilter
        paint.colorFilter = colorFilter
    }

    override fun getAlpha(): Int {
        return backgroundDrawable.alpha
    }

    override fun isStateful(): Boolean {
        return backgroundDrawable.isStateful
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int {
        return backgroundDrawable.opacity
    }
} 