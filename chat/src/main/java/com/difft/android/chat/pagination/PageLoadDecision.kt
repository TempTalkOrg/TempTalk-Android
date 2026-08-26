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

/**
 * Which trigger asked for a page load. Selects the gate behavior (IDLE waits out an in-flight
 * load, PREFETCH drops the check while the gate is busy) and doubles as the grouping key of the
 * load log line, so a paging measurement can tell prefetch-driven loads from IDLE-driven ones.
 */
internal enum class PageLoadTrigger(val tag: String) {
    PREFETCH("prefetch"),
    IDLE("idle"),
}

/** Whether a page load should start, per direction. Both false means "do nothing". */
internal data class PageLoadDecision(val loadNewer: Boolean, val loadOlder: Boolean) {
    companion object {
        val NONE = PageLoadDecision(loadNewer = false, loadOlder = false)
    }
}

/**
 * Rows from either edge at which a page load is kicked off — roughly half a screen of bubbles.
 *
 * The point of not waiting for the absolute edge is that the load can overlap the remaining
 * scrolling instead of the user watching an empty edge.
 */
internal const val PREFETCH_EDGE_ROWS = 10

/** Sampling period of the scroll-driven prefetch signal: leading edge, then one check per period. */
internal const val SCROLL_PREFETCH_THROTTLE_MS = 500L

/**
 * The whole page-load trigger decision, as a pure function of scalars the caller collects.
 *
 * [isAtBottom] arrives as an already-computed verdict: the Fragment keeps owning that predicate
 * verbatim (several other call sites depend on its exact meaning) and this function must never
 * re-derive it. No viewport reading crosses this boundary — only its result does.
 *
 * `NO_POSITION` (-1) is rejected explicitly. `-1 < PREFETCH_EDGE_ROWS` would otherwise start an
 * older-page load on an empty or not-yet-laid-out list, which the `firstVisible == 0` form this
 * replaces rejected implicitly.
 */
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
    // Programmatic scrolls (quote jump, mention, search, jump-to-bottom, Pop hand-off) must never
    // page: they place the viewport deliberately, and a load underneath them moves it.
    if (!userScrolling) return PageLoadDecision.NONE
    val nearBottom = isAtBottom ||
        (lastVisible != RecyclerView.NO_POSITION && lastVisible > itemCount - edgeRows)
    val nearTop = firstVisible != RecyclerView.NO_POSITION && firstVisible < edgeRows
    return PageLoadDecision(
        loadNewer = nearBottom && !hasReachedLatest,
        loadOlder = nearTop && !hasReachedHistoryStart,
    )
}

/**
 * Runs at most one page load round at a time, whichever direction it is in.
 *
 * [gate] is what makes overlap impossible: the per-direction `isLoadingTop` / `isLoadingBottom`
 * flags cannot stop a prefetch-driven older-page load and an IDLE-driven newer-page load from
 * interleaving, and the controller merges pages with a read-modify-write on its state flow, so an
 * overlap silently drops a whole page.
 *
 * @param waitIfBusy true waits out an in-flight round (the IDLE path: read receipts are sent right
 *   after this returns and must not be sent mid-pagination); false drops the check when the gate is
 *   busy (the prefetch path: the next signal or the next IDLE re-issues it).
 *
 * [decide] is called TWICE on purpose, and neither call may be removed. The pre-check keeps the
 * common "nothing to do" case from contending for the gate at all. The post-acquire call is what
 * makes the executed decision fresh: on the waiting path `lock()` suspends for as long as the
 * in-flight load takes, and that load changes item count, visible positions and both edge flags —
 * acting on the pre-check verdict would be harmless (an idempotent re-query, or a page no longer
 * needed) but silent. Recomputing costs three layout reads.
 */
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

/**
 * Collects the scroll-prefetch signal: emit-first then sample every [periodMillis], and only while
 * [lifecycle] is at least STARTED.
 *
 * `sampleAfterFirst` rather than `debounce` or plain `sample`: `debounce` would never fire during a
 * continuous fling, and plain `sample` would delay the first check of a fling by a whole period,
 * missing short flings entirely.
 *
 * [onCheck] runs inside its own try/catch because this collector is launched once per view lifecycle:
 * [onCheck] reaches IO-bound DB reads, and letting one transient failure escape would cancel the
 * collector and silently disable scroll prefetch for the rest of the view's life.
 */
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
