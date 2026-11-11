package com.difft.android.base.widget.sideBar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.difft.android.base.R
import com.difft.android.base.utils.dp
import java.util.Locale






class SectionDecoration(
    context: Context,
    private val callback: DecorationCallback,
    @ColorInt bandColor: Int = ContextCompat.getColor(context, R.color.bg3),
) : ItemDecoration() {
    private val textPaint: TextPaint
    private val paint: Paint = Paint()
    private val topGap: Int
    private val fontMetrics: Paint.FontMetrics = Paint.FontMetrics()

    init {
        paint.color = bandColor
        textPaint = TextPaint()
        textPaint.isAntiAlias = true
        textPaint.color = ContextCompat.getColor(context, R.color.t_primary)
        textPaint.textSize = 14.dp.toFloat()
        textPaint.getFontMetrics(fontMetrics)
        textPaint.textAlign = Paint.Align.LEFT
        topGap = 32.dp
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        super.getItemOffsets(outRect, view, parent, state)
        val index = parent.getChildAdapterPosition(view)
        val groupId = callback.getGroupId(index)
        if (groupId < 0) {
            return
        }
        if (groupId == 0L || isFirstInGroup(index)) {
            
            outRect.top = topGap
        } else {
            outRect.top = 0
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
    }

    private fun isFirstInGroup(pos: Int): Boolean {
        return if (pos == 0) {
            true
        } else {
            val lastGroupId = callback.getGroupId(pos - 1)
            val groupId = callback.getGroupId(pos)
            lastGroupId != groupId
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        val itemCount = state.itemCount
        val childCount = parent.childCount
        
        val scrollbarOnLeft = when (parent.verticalScrollbarPosition) {
            View.SCROLLBAR_POSITION_LEFT -> true
            View.SCROLLBAR_POSITION_RIGHT -> false
            else -> parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        }
        val scrollbarWidth = parent.verticalScrollbarWidth
        val left = (parent.paddingLeft + if (scrollbarOnLeft) scrollbarWidth else 0).toFloat()
        val right = (parent.width - parent.paddingRight - if (scrollbarOnLeft) 0 else scrollbarWidth).toFloat()
        val lineHeight = textPaint.textSize + fontMetrics.descent
        var lastGroupId: Long
        var groupId: Long = -1
        for (i in 0 until childCount) {
            val view = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(view)
            lastGroupId = groupId
            groupId = callback.getGroupId(position)
            if (groupId < 0 || lastGroupId == groupId) {
                continue
            }
            
            
            val rawLine = callback.getGroupFirstLine(position)
            val textLine = if (rawLine.length == 1) rawLine.uppercase(Locale.getDefault()) else rawLine
            if (TextUtils.isEmpty(textLine)) {
                continue
            }
            val viewBottom = view.bottom
            
            var textY = Math.max(parent.paddingTop + topGap, view.top).toFloat()
            if (position + 1 < itemCount) {
                
                val nextGroupId = callback.getGroupId(position + 1)
                if (nextGroupId != groupId && viewBottom < textY) {
                    textY = viewBottom.toFloat()
                }
            }
            c.drawRect(left, textY - topGap, right, textY, paint)
            val baseline = textY - 10.dp
            val icon = callback.getGroupIcon(position)
            if (icon != null) {
                
                val iconSize = 14.dp
                val iconLeft = 16.dp
                val textCenterY = baseline + (fontMetrics.ascent + fontMetrics.descent) / 2f
                val iconTop = (textCenterY - iconSize / 2f).toInt()
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                icon.draw(c)
                
                c.drawText(textLine, (iconLeft + iconSize + 6.dp).toFloat(), baseline, textPaint)
            } else {
                c.drawText(textLine, 16.dp.toFloat(), baseline, textPaint)
            }
        }
    }

    interface DecorationCallback {
        fun getGroupId(position: Int): Long
        fun getGroupFirstLine(position: Int): String

        
        fun getGroupIcon(position: Int): Drawable? = null
    }
}