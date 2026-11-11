package com.difft.android.chat.translate.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.translate.VisibleReason
import com.difft.android.chat.ui.ChatMessageAdapter












internal class ChatTranslateListBinder(
    private val recyclerView: RecyclerView,
    private val adapter: ChatMessageAdapter,
    private val onVisibleRange: (List<String>, VisibleReason) -> Unit,
) {
    private var lastCommittedIds: Set<String> = emptySet()

    fun onFirstRender() = report(VisibleReason.FIRST_SCREEN)

    fun onScrollIdle() = report(VisibleReason.SCROLL_IDLE)

    fun onListCommitted() {
        val ids = adapter.currentList.mapTo(HashSet()) { it.id }
        val broughtNewIds = ids.any { it !in lastCommittedIds }
        lastCommittedIds = ids
        if (broughtNewIds) report(VisibleReason.WINDOW_CHANGED)
    }

    private fun report(reason: VisibleReason) {
        val ids = visibleIds()
        if (ids.isNotEmpty()) onVisibleRange(ids, reason)
    }

    



    private fun visibleIds(): List<String> {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return emptyList()
        val first = layoutManager.findFirstCompletelyVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION || last < first) {
            return emptyList()
        }
        val list = adapter.currentList
        return (first..last).mapNotNull { list.getOrNull(it)?.id }
    }
}
