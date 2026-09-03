package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #1..#4 and #15 — `ChatMessageListBehavior.hasReachedHistoryStart` across
 * [ChatNormalPaginationController]'s pagination call sites.
 *
 * These used to be `@Ignore`d: the controller's own constructor built a WCDB winq `Expression`,
 * whose static initializer calls `System.loadLibrary("WCDB")`, and that .so has no host-JVM build.
 * `ChatMessageWindowSource` removed that constructor-time dependency, so the cases now run for
 * real against `FakeChatMessageWindowSource` instead of a hand-sequenced `returnsMany` mock.
 *
 * `MAX_MESSAGE_COUNT` is referenced as a SYMBOL on purpose — never as the literal 60. Retuning the
 * window size must re-tune these cases with it, not silently strand them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerHasReachedHistoryStartTest : PaginationTestBase() {

    // #1 — nothing older exists: loadPreviousPage() returns false and the emission claims the
    // start of history.
    @Test
    fun `loadPreviousPage with no older row sets hasReachedHistoryStart true and returns false`() = runTest {
        source.seed(listOf(buildMessageModel("m1", 1_000L)))
        val controller = controller()

        val hasOlder = controller.loadPreviousPage()

        assertFalse(hasOlder)
        assertTrue(controller.behavior().hasReachedHistoryStart)
    }

    // #2 (hasReachedHistoryStart half) — older rows remain beyond the page just loaded: the
    // emission must NOT claim the start of history and the return value must stay positive.
    //
    // The window has to be full enough that the previous-page query hits its own limit; with a
    // two-message conversation every older row lands in the window and the assertion inverts.
    @Test
    fun `loadPreviousPage with older rows left sets hasReachedHistoryStart false and returns true`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT))
        val controller = controller()
        controller.jumpToBottom()

        val hasOlder = controller.loadPreviousPage()

        assertTrue(hasOlder)
        assertFalse(controller.behavior().hasReachedHistoryStart)
        // Not truncated at the newest end — the window is still below the cap.
        assertTrue(controller.window().size <= MAX_MESSAGE_COUNT)
        assertEquals(null, controller.behavior().anchorMessageAfter)
    }

    // #3 — paging forward evicts the true-oldest row via takeLast(MAX_MESSAGE_COUNT);
    // hasReachedHistoryStart must be recomputed, not carried forward.
    @Test
    fun `loadNextPage flips hasReachedHistoryStart false once the true-oldest message is evicted`() = runTest {
        val oldest = buildMessageModel("old", 100L)
        source.seed(listOf(oldest) + buildMessageSequence(MAX_MESSAGE_COUNT, startTs = 200L, idPrefix = "new"))
        val controller = controller()

        // Open the window ON the true-oldest row, so it starts inside the window.
        assertTrue(controller.jumpToMessage(oldest.timeStamp))
        assertTrue(controller.behavior().hasReachedHistoryStart)

        var guard = 0
        while ("old" in controller.windowIds() && guard++ < MAX_PAGE_FORWARD_STEPS) {
            controller.loadNextPage()
        }

        val finalWindow = controller.window()
        assertEquals(MAX_MESSAGE_COUNT, finalWindow.size)
        assertFalse("old" in controller.windowIds())
        assertFalse(controller.behavior().hasReachedHistoryStart)
    }

    // #4 — addOneMessage is an in-memory append: it carries the signal forward and issues no query.
    @Test
    fun `addOneMessage carries hasReachedHistoryStart forward without querying`() = runTest {
        source.seed(listOf(buildMessageModel("m1", 1_000L)))
        val controller = controller()
        controller.loadPreviousPage()
        assertTrue(controller.behavior().hasReachedHistoryStart)
        val countsBefore = source.callCount("countOlderThan")

        controller.addOneMessage(buildMessageModel("m2", 2_000L))

        assertTrue(controller.behavior().hasReachedHistoryStart)
        assertEquals(countsBefore, source.callCount("countOlderThan"))
    }

    // #15 — CRIT-1 guard. A row inserted OLDER than the window is invisible to the observer's own
    // window query, so only re-running the COUNT on every emission can notice it. Caching
    // hasReachedHistoryStart would leave the E2EE header wrongly pinned to the top forever.
    @Test
    fun `an insert older than the window flips hasReachedHistoryStart back and re-runs the count`() = runTest {
        source.seed(buildMessageSequence(5))
        val controller = controller()
        controller.loadPreviousPage()
        assertTrue(controller.behavior().hasReachedHistoryStart)
        val before = controller.behavior()
        val countsBefore = source.callCount("countOlderThan")

        source.insertOlderAndNotify(buildMessageModel("older", 1L))
        val after = controller.awaitNextBehavior(before)

        assertFalse(after.hasReachedHistoryStart)
        assertEquals(countsBefore + 1, source.callCount("countOlderThan"))
    }

    private companion object {
        /** Bound on the forward-paging loop above; a correct controller needs far fewer. */
        const val MAX_PAGE_FORWARD_STEPS = 10
    }
}
