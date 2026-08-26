package com.difft.android.chat.pagination

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #77..#80 — the page-load trigger decision.
 *
 * The whole reason this is a pure function is here: the `userScrolling` gate that keeps the five
 * programmatic jump paths from paging can be exhausted, and the `NO_POSITION` trap the
 * `firstVisible == 0` form used to reject implicitly becomes an ordinary case.
 */
class PageLoadDecisionTest {

    // #77 — top threshold, including the -1 trap: `-1 < PREFETCH_EDGE_ROWS` is TRUE, so an empty or
    // not-yet-laid-out list would start loading history without the explicit rejection.
    @Test
    fun `the top threshold fires inside the edge band and never on NO_POSITION`() {
        val expected = mapOf(
            RecyclerView.NO_POSITION to false,
            0 to true,
            PREFETCH_EDGE_ROWS - 1 to true,
            PREFETCH_EDGE_ROWS to false,
            PREFETCH_EDGE_ROWS + 1 to false,
        )
        expected.forEach { (firstVisible, loadOlder) ->
            val decision = decide(firstVisible = firstVisible)
            assertEquals("firstVisible=$firstVisible", loadOlder, decision.loadOlder)
        }
    }

    // #78 — bottom threshold. `isAtBottom` is passed in as an already-computed verdict and short
    // circuits the row arithmetic; it must NOT be widened to mean "near the bottom" (several other
    // consumers, including the trim gate and the auto-snap capture, need its strict meaning).
    @Test
    fun `the bottom threshold fires inside the edge band and never on NO_POSITION`() {
        val expected = mapOf(
            RecyclerView.NO_POSITION to false,
            ITEM_COUNT - PREFETCH_EDGE_ROWS to false,
            ITEM_COUNT - PREFETCH_EDGE_ROWS + 1 to true,
            ITEM_COUNT - 1 to true,
        )
        expected.forEach { (lastVisible, loadNewer) ->
            val decision = decide(lastVisible = lastVisible, isAtBottom = false)
            assertEquals("lastVisible=$lastVisible", loadNewer, decision.loadNewer)
        }
    }

    @Test
    fun `isAtBottom alone fires the newer load whatever the visible positions are`() {
        listOf(RecyclerView.NO_POSITION, 0, ITEM_COUNT / 2).forEach { lastVisible ->
            assertTrue(
                "lastVisible=$lastVisible",
                decide(lastVisible = lastVisible, isAtBottom = true).loadNewer,
            )
        }
    }

    // #79 — HARD CONSTRAINT: a programmatic scroll must never page. All five jump landings satisfy a
    // threshold, and `userScrolling == false` is the only thing standing between them and a load
    // that moves the viewport they just placed.
    @Test
    fun `no programmatic scroll landing triggers a load`() {
        val landings = mapOf(
            // quote tap: target lands mid-window
            "quote" to Landing(firstVisible = 4, lastVisible = 12, itemCount = 180, isAtBottom = false),
            // mention FAB: target near the top of the window
            "mention" to Landing(firstVisible = 0, lastVisible = 8, itemCount = 180, isAtBottom = false),
            // search jump: fresh window, target at its start
            "search" to Landing(firstVisible = 1, lastVisible = 9, itemCount = 20, isAtBottom = false),
            // jump-to-bottom FAB
            "jumpToBottom" to Landing(firstVisible = 12, lastVisible = 19, itemCount = 20, isAtBottom = true),
            // Pop -> full screen hand-off by timestamp
            "popHandoff" to Landing(firstVisible = 0, lastVisible = 5, itemCount = 20, isAtBottom = false),
        )
        landings.forEach { (name, landing) ->
            val decision = decidePageLoad(
                userScrolling = false,
                firstVisible = landing.firstVisible,
                lastVisible = landing.lastVisible,
                itemCount = landing.itemCount,
                isAtBottom = landing.isAtBottom,
                hasReachedHistoryStart = false,
                hasReachedLatest = false,
            )
            assertEquals("$name must not page", PageLoadDecision.NONE, decision)
        }
    }

    // #80 — the two edge flags gate their own direction only, so the threshold change and the
    // edge-memory gating can be reverted independently of each other.
    @Test
    fun `each edge flag suppresses only its own direction`() {
        val decision = decidePageLoad(
            userScrolling = true,
            firstVisible = 3,
            lastVisible = ITEM_COUNT - 1,
            itemCount = ITEM_COUNT,
            isAtBottom = true,
            hasReachedHistoryStart = true,
            hasReachedLatest = false,
        )
        assertFalse("history start reached: no older page", decision.loadOlder)
        assertTrue("newest end not reached: newer page still allowed", decision.loadNewer)
    }

    private fun decide(
        userScrolling: Boolean = true,
        firstVisible: Int = MID_WINDOW,
        lastVisible: Int = MID_WINDOW,
        itemCount: Int = ITEM_COUNT,
        isAtBottom: Boolean = false,
        hasReachedHistoryStart: Boolean = false,
        hasReachedLatest: Boolean = false,
    ) = decidePageLoad(
        userScrolling = userScrolling,
        firstVisible = firstVisible,
        lastVisible = lastVisible,
        itemCount = itemCount,
        isAtBottom = isAtBottom,
        hasReachedHistoryStart = hasReachedHistoryStart,
        hasReachedLatest = hasReachedLatest,
    )

    private data class Landing(
        val firstVisible: Int,
        val lastVisible: Int,
        val itemCount: Int,
        val isAtBottom: Boolean,
    )

    private companion object {
        const val ITEM_COUNT = 180
        /** Far from either edge band, so a default-valued scalar never fires a load by itself. */
        const val MID_WINDOW = 90
    }
}
