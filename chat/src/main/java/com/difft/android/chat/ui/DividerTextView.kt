package com.difft.android.chat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.difft.android.base.utils.dp

class DividerTextView(context: Context, attrs: AttributeSet) : AppCompatTextView(context, attrs) {

    private val paint = Paint().apply {
        color = Color.YELLOW  // 分割线颜色
        strokeWidth = 4.dp.toFloat()    // 分割线宽度
    }

    private val offset = 0f

    private var isDividerEnabled = true  // 是否启用分割线，默认为启用

    fun setDividerEnabled(enabled: Boolean) {
        isDividerEnabled = enabled
        invalidate()  // 重绘视图，确保更新启用状态
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isDividerEnabled) {
            val layout = layout  // 获取TextView的布局
            val lineCount = layout.lineCount  // 获取行数

            // 遍历每一行，绘制行与行之间的分割线
            for (i in 0 until lineCount - 1) {  // 不绘制最后一行之后的分割线
                // 计算左边的偏移
                val lineStartX = 0f  // 左边偏移12dp
                val lineEndX = width.toFloat() // 右边不偏移
                // 计算底部偏移
                val lineBottom = layout.getLineBottom(i).toFloat() + offset  // 底部偏移12dp
                // 计算下一行的顶部偏移
                val nextLineTop = layout.getLineTop(i + 1).toFloat() - offset  // 下一行顶部偏移12dp

                // 绘制行与行之间的分割线
                canvas.drawLine(lineStartX, lineBottom, lineEndX, lineBottom, paint)
//                // 如果想要在两行之间的空隙中加入偏移，也可以再绘制一条线
//                it.drawLine(lineStartX, nextLineTop, lineEndX, nextLineTop, paint)
            }
        } else {
            super.onDraw(canvas)
        }
    }

    fun setDividerColor(color: Int) {
        paint.color = color
        invalidate()  // 重绘视图，确保分割线颜色更新
    }
}


