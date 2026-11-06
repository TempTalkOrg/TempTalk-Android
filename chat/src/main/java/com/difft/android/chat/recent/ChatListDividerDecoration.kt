package com.difft.android.chat.recent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.R

/**
 * Hairline between two consecutive conversation rows of the chat list (design-system list
 * spec: inset to the text column, none after the last row). Drawn over the rows so the swipe
 * actions and the selected-row background keep their own geometry; rows that are not
 * conversations (search input, E2EE footer) never get a line above or below.
 */
class ChatListDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {

    // Activity density, not the application's: the two differ on multi-display foldables.
    private val density = context.resources.displayMetrics.density
    // One physical pixel, like the design system's 0.5px hairline and iOS's separator; a
    // density-scaled 0.5dp reads as a 2px rule on 3x screens.
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.line)
        strokeWidth = 1f
    }
    private val insetStartPx = (TEXT_COLUMN_START_DP * density).toInt()
    private val insetEndPx = (ROW_END_MARGIN_DP * density).toInt()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter ?: return
        val rtl = parent.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val left = (if (rtl) insetEndPx else insetStartPx).toFloat() + parent.paddingLeft
        val right = parent.width - parent.paddingRight - (if (rtl) insetStartPx else insetEndPx).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position + 1 >= adapter.itemCount) continue
            if (!adapter.isConversationRow(position) || !adapter.isConversationRow(position + 1)) continue
            val y = child.bottom + child.translationY - paint.strokeWidth / 2f
            c.drawLine(left + child.translationX, y, right + child.translationX, y, paint)
        }
    }

    private fun RecyclerView.Adapter<*>.isConversationRow(position: Int): Boolean =
        when (getItemViewType(position)) {
            RecentChatAdapter.VIEW_TYPE_CHAT_ITEM,
            RecentChatAdapter.VIEW_TYPE_FOCUS_CHAT,
            RecentChatAdapter.VIEW_TYPE_FOLDED_GROUPS -> true
            else -> false
        }

    private companion object {
        /** 16dp avatar margin + 52dp avatar + 12dp gap: where the row's text column starts. */
        const val TEXT_COLUMN_START_DP = 80
        const val ROW_END_MARGIN_DP = 16
    }
}
