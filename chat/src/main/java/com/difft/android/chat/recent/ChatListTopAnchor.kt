package com.difft.android.chat.recent

import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView










internal fun <T> ListAdapter<T, *>.submitListKeepingTop(list: RecyclerView, items: List<T>) {
    submitList(items) {
        if (list.isAttachedToWindow && !list.canScrollVertically(-1)) list.scrollToPosition(0)
    }
}
