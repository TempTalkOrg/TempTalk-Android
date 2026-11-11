package com.difft.android.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.R







class SearchResultDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {

    
    private val density = context.resources.displayMetrics.density

    
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = context.resources.getDimension(R.dimen.hairline)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? GlobalSearchResultsAdapter ?: return
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || !adapter.showsDivider(position)) continue
            val inset = adapter.textColumnStartDp(position) * density
            val left = parent.paddingLeft + if (rtl) 0f else inset
            val right = parent.width - parent.paddingRight - if (rtl) inset else 0f
            val y = child.bottom + child.translationY - paint.strokeWidth / 2f
            c.drawLine(left + child.translationX, y, right + child.translationX, y, paint)
        }
    }
}
