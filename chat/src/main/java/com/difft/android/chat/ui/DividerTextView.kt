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
        color = Color.YELLOW
        strokeWidth = 4.dp.toFloat()
    }

    private val offset = 0f

    private var isDividerEnabled = true

    fun setDividerEnabled(enabled: Boolean) {
        isDividerEnabled = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isDividerEnabled) {
            val layout = layout
            val lineCount = layout.lineCount

            for (i in 0 until lineCount - 1) {
                val lineStartX = 0f
                val lineEndX = width.toFloat()
                val lineBottom = layout.getLineBottom(i).toFloat() + offset
                val nextLineTop = layout.getLineTop(i + 1).toFloat() - offset

                canvas.drawLine(lineStartX, lineBottom, lineEndX, lineBottom, paint)
            }
        } else {
            super.onDraw(canvas)
        }
    }

    fun setDividerColor(color: Int) {
        paint.color = color
        invalidate()
    }
}


