package com.difft.android.chat.recent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.difft.android.chat.R


private const val UNREAD_BADGE_SIZE_DP = 18
private const val UNREAD_BADGE_PADDING_DP = 3


private const val UNREAD_DOT_SIZE_DP = 10


internal const val FOCUS_CARD_LINES = 3






internal fun applyUnreadBadge(badge: TextView, missedNumber: Int, isMuted: Boolean, isDot: Boolean) {
    if (missedNumber == 0) {
        badge.visibility = View.GONE
        return
    }
    badge.visibility = View.VISIBLE
    badge.setBackgroundResource(
        if (isMuted) R.drawable.chat_missing_number_bg_muted else R.drawable.chat_missing_number_bg
    )
    val density = badge.resources.displayMetrics.density
    val sizePx = ((if (isDot) UNREAD_DOT_SIZE_DP else UNREAD_BADGE_SIZE_DP) * density).toInt()
    val paddingPx = if (isDot) 0 else (UNREAD_BADGE_PADDING_DP * density).toInt()
    badge.updateLayoutParams {
        height = sizePx
        width = if (isDot) sizePx else ViewGroup.LayoutParams.WRAP_CONTENT
    }
    badge.minWidth = if (isDot) 0 else sizePx
    badge.minHeight = if (isDot) 0 else sizePx
    badge.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    if (isDot) badge.text = ""
}







internal fun focusCardLines(draft: CharSequence?, previews: List<String>, fallback: CharSequence?): List<CharSequence?> = when {
    previews.isEmpty() -> listOf(draft ?: fallback)
    draft != null -> listOf(draft) + previews.takeLast(FOCUS_CARD_LINES - 1)
    else -> previews.takeLast(FOCUS_CARD_LINES)
}





internal fun bindFocusSwipeAction(container: View, icon: ImageView, label: TextView, action: FocusRowAction) {
    container.isVisible = action != FocusRowAction.NONE
    if (action == FocusRowAction.NONE) return
    val context = container.context
    icon.setImageResource(R.drawable.chat_ic_focus)
    label.text = when (action) {
        FocusRowAction.START -> context.getString(R.string.focus_list_swipe_focus)
        else -> {
            val full = context.getString(R.string.focus_dialog_end_focus)
            val res = context.resources
            val available = res.getDimensionPixelSize(R.dimen.chat_list_swipe_action_width) -
                2 * res.getDimensionPixelSize(R.dimen.chat_list_swipe_action_label_padding)
            if (label.paint.measureText(full) > available) context.getString(R.string.focus_list_swipe_end_short) else full
        }
    }
}
