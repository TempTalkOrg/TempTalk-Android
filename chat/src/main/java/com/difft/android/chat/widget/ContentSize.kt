package com.difft.android.chat.widget

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.R



























internal fun View.chatContainerWidthPx(): Int {
    var nearestFullWidthPx = 0
    var ancestor: ViewGroup? = parent as? ViewGroup
    while (ancestor != null) {
        if (ancestor.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            val contentWidthPx = ancestor.measuredWidth - ancestor.paddingLeft - ancestor.paddingRight
            if (contentWidthPx > 0) {
                if (ancestor is RecyclerView) return contentWidthPx
                if (nearestFullWidthPx == 0) nearestFullWidthPx = contentWidthPx
            }
        }
        ancestor = ancestor.parent as? ViewGroup
    }
    return if (nearestFullWidthPx > 0) nearestFullWidthPx else resources.displayMetrics.widthPixels
}









fun View.chatContentWidthPx(): Int =
    minOf(chatContainerWidthPx(), resources.getDimensionPixelSize(R.dimen.chat_content_max_width))






fun View.chatContentHeightPx(): Int = resources.displayMetrics.heightPixels
