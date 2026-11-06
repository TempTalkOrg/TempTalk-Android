package com.difft.android.chat.recent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.difft.android.chat.R

/** XML size of textview_missed_number (18dp, padding 3dp); restored after a dot render. */
private const val UNREAD_BADGE_SIZE_DP = 18
private const val UNREAD_BADGE_PADDING_DP = 3

/** Manual-unread indicator: a dot with no number. */
private const val UNREAD_DOT_SIZE_DP = 10

/** A focus card shows at most this many previews. */
internal const val FOCUS_CARD_LINES = 3

/**
 * Unread badge of a chat-list row: red, or grey when [isMuted]. [isDot]: a manual "mark as
 * unread" with no real unread renders as a plain dot (no number), per the chat-list gesture
 * design; the same view shrinks to [UNREAD_DOT_SIZE_DP] and grows back to the XML badge size for
 * a count, since holders are recycled between the two.
 */
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

/**
 * Preview lines of the focus card, top to bottom. The latest previews come oldest first, so the
 * newest message sits at the bottom; a draft takes the first line (where the `[Draft]` tag is)
 * and the two newest previews follow. Without prefetched previews (synthesized row) the card
 * falls back to the one-line preview.
 */
internal fun focusCardLines(draft: CharSequence?, previews: List<String>, fallback: CharSequence?): List<CharSequence?> = when {
    previews.isEmpty() -> listOf(draft ?: fallback)
    draft != null -> listOf(draft) + previews.takeLast(FOCUS_CARD_LINES - 1)
    else -> previews.takeLast(FOCUS_CARD_LINES)
}

/**
 * The swipe button between pin and delete: "Focus" or "End Focus" on a white-on-bgspace button.
 * "End Focus" falls back to "End" when it does not fit the button (PRD 7.4.4).
 */
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
