package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.SCROLL_PAGE_SIZE
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #10 and #14 — the change observer: exactly one live collector across restarts, and the
 * unbounded `ge(min)` re-query never dropping rows the user is looking at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerObserverTest : PaginationTestBase() {

    // #10 — each emission restarts the observer. `observeMessageChangesJob?.cancelAndJoin()` is what
    // keeps the previous collector from surviving; losing it means every change signal is handled
    // once per past page load.
    @Test
    fun `restarting the observer leaves exactly one live collector`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT))
        val controller = controller()
        controller.loadPreviousPage()
        controller.loadPreviousPage()
        val before = controller.behavior()

        source.notifyChange()
        controller.awaitNextBehavior(before)

        assertEquals(1, source.activeChangeCollectors)
        assertEquals(1, source.callCount("ascendingFrom"))
    }

    // #14 — CRIT-2 at the controller layer. The user has scrolled back into the middle of the
    // window, so the observer is NOT restarted; a burst of new messages arrives. The observer's
    // `ge(min)` branch must keep absorbing at the newest end and must never drop rows off the
    // oldest end — those are the rows currently on screen.
    @Test
    fun `a burst of new messages never drops the rows the viewport is showing`() = runTest {
        val seeded = buildMessageSequence(400)
        source.seed(seeded)
        val controller = controller()
        controller.jumpToBottom()
        // Filled to just under the cap: a window that had been truncated at the newest end would no
        // longer contain the conversation's newest row, which freezes the observer to its own time
        // range — the incoming burst below would then be invisible for a different reason.
        controller.fillWindowBelowCap()
        assertTrue(
            "the window must be nearly full for this case to mean anything",
            controller.window().size > MAX_MESSAGE_COUNT - SCROLL_PAGE_SIZE,
        )

        // "The user is parked at rows 3..12 of the window" — well away from either edge.
        val viewport = controller.window().subList(3, 13)

        val incoming = buildMessageSequence(
            count = 20,
            startTs = seeded.last().systemShowTimestamp + 1_000L,
            idPrefix = "incoming",
        )
        incoming.forEach { source.appendAndNotify(it) }
        val after = controller.awaitBehaviorWhere { behavior ->
            behavior.messageList.any { it.id == incoming.last().id }
        }

        assertViewportRetained(viewport, after.messageList)
    }
}
