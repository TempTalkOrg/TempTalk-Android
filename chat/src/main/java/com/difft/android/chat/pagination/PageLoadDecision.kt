package com.difft.android.chat.pagination

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex






internal enum class PageLoadTrigger(val tag: String) {
    PREFETCH("prefetch"),
    IDLE("idle"),
}


internal data class PageLoadDecision(val loadNewer: Boolean, val loadOlder: Boolean) {
    companion object {
        val NONE = PageLoadDecision(loadNewer = false, loadOlder = false)
    }
}







internal const val PREFETCH_EDGE_ROWS = 10


internal const val SCROLL_PREFETCH_THROTTLE_MS = 500L











internal fun decidePageLoad(
    userScrolling: Boolean,
    firstVisible: Int,
    lastVisible: Int,
    itemCount: Int,
    isAtBottom: Boolean,
    hasReachedHistoryStart: Boolean,
    hasReachedLatest: Boolean,
    edgeRows: Int = PREFETCH_EDGE_ROWS,
): PageLoadDecision {
    
    
    if (!userScrolling) return PageLoadDecision.NONE
    val nearBottom = isAtBottom ||
        (lastVisible != RecyclerView.NO_POSITION && lastVisible > itemCount - edgeRows)
    val nearTop = firstVisible != RecyclerView.NO_POSITION && firstVisible < edgeRows
    return PageLoadDecision(
        loadNewer = nearBottom && !hasReachedLatest,
        loadOlder = nearTop && !hasReachedHistoryStart,
    )
}




















internal suspend fun runGatedPageLoad(
    gate: Mutex,
    waitIfBusy: Boolean,
    decide: () -> PageLoadDecision,
    loadNewer: suspend () -> Unit,
    loadOlder: suspend () -> Unit,
) {
    if (decide() == PageLoadDecision.NONE) return
    if (waitIfBusy) gate.lock() else if (!gate.tryLock()) return
    try {
        val decision = decide()
        if (decision.loadNewer) loadNewer()
        if (decision.loadOlder) loadOlder()
    } finally {
        gate.unlock()
    }
}













internal fun CoroutineScope.launchScrollPrefetch(
    signals: Flow<Unit>,
    lifecycle: Lifecycle,
    periodMillis: Long = SCROLL_PREFETCH_THROTTLE_MS,
    onCheck: suspend () -> Unit,
): Job = launch {
    signals
        .sampleAfterFirst(periodMillis)
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .collect {
            try {
                onCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[message] scroll prefetch check failed: ${e.stackTraceToString()}" }
            }
        }
}
