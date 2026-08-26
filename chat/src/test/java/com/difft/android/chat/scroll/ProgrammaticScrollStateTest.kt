package com.difft.android.chat.scroll

import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.chat.scroll.testing.RecyclerViewScrollHarness
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case #12 — FRAMEWORK ASSUMPTION test.
 *
 * Five programmatic landing paths (quote jump, mention jump, search jump, jump-to-bottom,
 * Pop → full-screen hand-off) plus three geometry compensations rely on one unwritten property of
 * AndroidX RecyclerView: a programmatic scroll never dispatches `SCROLL_STATE_DRAGGING`, so
 * `userScrolling` stays false and the IDLE block does not kick off a page load. That property is
 * an AndroidX implementation detail and would break silently on a library upgrade.
 *
 * SCOPE — programmatic paths only. `DRAGGING` is produced exclusively by real touch input, which
 * Robolectric does not faithfully simulate; that half belongs to on-device QA.
 */
class ProgrammaticScrollStateTest : RecyclerViewScrollHarness() {

    @Test
    fun `programmatic scrolling never dispatches DRAGGING`() {
        submitAndLayout(itemCount = 60)

        recyclerView.scrollToPosition(0)
        layoutRecyclerView()
        idleLooper()

        recyclerView.scrollBy(0, -800)
        idleLooper()

        recyclerView.noSmoothScrollToBottom()
        layoutRecyclerView()
        idleLooper()

        recyclerView.smoothScrollToPosition(0)
        idleLooper()

        val allowed = setOf(RecyclerView.SCROLL_STATE_SETTLING, RecyclerView.SCROLL_STATE_IDLE)
        assertTrue(
            "unexpected scroll states dispatched: $observedStates",
            observedStates.all { it in allowed },
        )
        assertNeverDragged()
    }
}
