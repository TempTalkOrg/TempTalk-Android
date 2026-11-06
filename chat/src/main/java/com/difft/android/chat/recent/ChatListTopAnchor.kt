package com.difft.android.chat.recent

import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * [ListAdapter.submitList] for a list whose first row can change: when the list sits at its top,
 * it stays there. Without a fixed first row, LinearLayoutManager anchors on the old first row, so
 * a conversation that moves to position 0 would be laid out above the viewport.
 *
 * The top is read when the diff commits, not when it is submitted, so a scroll made while the diff
 * was computing wins. The commit runs before the next layout pass, so the scroll offset still
 * describes what is on screen.
 */
internal fun <T> ListAdapter<T, *>.submitListKeepingTop(list: RecyclerView, items: List<T>) {
    submitList(items) {
        if (list.isAttachedToWindow && !list.canScrollVertically(-1)) list.scrollToPosition(0)
    }
}
