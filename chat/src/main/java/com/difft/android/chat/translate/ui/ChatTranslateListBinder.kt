package com.difft.android.chat.translate.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.translate.VisibleReason
import com.difft.android.chat.ui.ChatMessageAdapter

/**
 * Fragment-side wiring for auto-translate: reports which messages the user can actually see, at the
 * three moments that can change it.
 *
 * Deliberately NOT on every list commit: read receipts, reactions and send-status updates commit a
 * list every few hundred milliseconds, and reporting each one would restart the visible-range batch
 * continuously. A commit only counts when it actually brought new ids into the window.
 *
 * Owned by the Fragment's view lifecycle — it nulls its reference in `onDestroyView`, so a late
 * RecyclerView callback can never touch a destroyed view tree.
 */
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

    /**
     * The first COMPLETELY visible row through the last partially visible one: a half-scrolled top
     * row that grows by a translate block would push the viewport down under the user's finger.
     */
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
