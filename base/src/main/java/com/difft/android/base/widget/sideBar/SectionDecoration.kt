package com.difft.android.base.widget.sideBar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.difft.android.base.R
import com.difft.android.base.utils.dp
import java.util.Locale

class SectionDecoration(context: Context, private val callback: DecorationCallback) : ItemDecoration() {
    private val textPaint: TextPaint
    private val paint: Paint = Paint()
    private val topGap: Int
    private val fontMetrics: Paint.FontMetrics = Paint.FontMetrics()

    init {
        paint.color = context.resources.getColor(R.color.bg2)
        textPaint = TextPaint()
        textPaint.isAntiAlias = true
        textPaint.color = context.resources.getColor(R.color.t_primary)
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
            //同组的第一个才添加padding
            outRect.top = topGap
        } else {
            outRect.top = 0
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        //        int left = parent.getPaddingLeft();
//        int right = parent.getWidth() - parent.getPaddingRight();
//
//        int count = parent.getChildCount();
//        for (int i = 0; i < count; i++) {
//            View view = parent.getChildAt(i);
//            int index = parent.getChildAdapterPosition(view);
//            long groupId = callback.getGroupId(index);
//            if (groupId < 0) {
//                return;
//            }
//
//            String textLine = callback.getGroupFirstLine(index).toUpperCase();
//            if (index == 0 || isFirstInGroup(index)) {
//                int top = view.getTop() - topGap;
//                int bottom = view.getTop();
//                c.drawRect(left, top, right, bottom, paint);
//                c.drawText(textLine, left, bottom, textPaint);
//            }
//        }
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
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight - 3.dp).toFloat()
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
            val textLine = callback.getGroupFirstLine(position).uppercase(Locale.getDefault())
            if (TextUtils.isEmpty(textLine)) {
                continue
            }
            val viewBottom = view.bottom
            var textY = Math.max(topGap, view.top).toFloat()
            if (position + 1 < itemCount) {
                //下一个和当前不一样，移动当前
                val nextGroupId = callback.getGroupId(position + 1)
                if (nextGroupId != groupId && viewBottom < textY) {
                    //组内最后一个view进入了header
                    textY = viewBottom.toFloat()
                }
            }
            c.drawRect(left, textY - topGap, right, textY, paint)
            c.drawText(textLine, 16.dp.toFloat(), textY - 10.dp, textPaint)
        }
    }

    interface DecorationCallback {
        fun getGroupId(position: Int): Long
        fun getGroupFirstLine(position: Int): String
    }
}