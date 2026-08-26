package com.difft.android.chat.pagination.testing

import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.ChatNormalPaginationController
import com.difft.android.chat.ScrollAction
import difft.android.messageserialization.For
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shared base for controller-level cases: one fixture source, one way to build the controller, one
 * assertion vocabulary.
 *
 * The controller is bound to `runTest`'s `backgroundScope` on purpose. The pre-seam cases used
 * `CoroutineScope(UnconfinedTestDispatcher())` — an independent scheduler that outlives the test,
 * which leaves the observer job parked inside `sampleAfterFirst`'s ticker and turns
 * `cancelAndJoin()` into an intermittent hang instead of a failure. `backgroundScope` is cancelled
 * when the test body ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PaginationTestBase {

    protected val source = FakeChatMessageWindowSource()
    protected val forWhat: For = For.Account(PEER_ID)

    protected fun TestScope.controller(myId: String = MY_ID): ChatNormalPaginationController =
        ChatNormalPaginationController(forWhat, source, myId).apply {
            bindCoroutineScope(backgroundScope)
        }

    // --- assertion vocabulary ---

    protected fun ChatNormalPaginationController.behavior(): ChatMessageListBehavior =
        requireNotNull(chatMessagesStateFlow.value) { "no ChatMessageListBehavior has been emitted yet" }

    protected fun ChatNormalPaginationController.window(): List<MessageModel> = behavior().messageList

    protected fun ChatNormalPaginationController.windowIds(): List<String> = window().map { it.id }

    /**
     * Pages backwards until the window sits at its cap — the only way to reach a window that
     * truncates at the newest end, and therefore the precondition of every both-anchors case.
     *
     * A loop rather than a fixed repeat count: the number of pages needed is
     * `MAX_MESSAGE_COUNT / SCROLL_PAGE_SIZE`, and hard-coding it would strand these cases on the
     * next re-tune of either constant.
     */
    protected suspend fun ChatNormalPaginationController.fillWindowToCap(maxSteps: Int = 20) {
        var steps = 0
        while (window().size < ChatNormalPaginationController.MAX_MESSAGE_COUNT && steps++ < maxSteps) {
            if (!loadPreviousPage()) break
        }
    }

    /**
     * Pages backwards only while the next page still fits under the cap, so the window keeps its
     * newest end intact.
     *
     * That distinction decides which branch the change observer takes: a window that no longer
     * contains the conversation's newest row is FROZEN to its own time range, and rows arriving after
     * it are invisible to the re-query. Cases about absorbing incoming messages need this variant,
     * cases about truncation need [fillWindowToCap].
     */
    protected suspend fun ChatNormalPaginationController.fillWindowBelowCap(maxSteps: Int = 20) {
        var steps = 0
        while (
            window().size + ChatNormalPaginationController.SCROLL_PAGE_SIZE <=
            ChatNormalPaginationController.MAX_MESSAGE_COUNT && steps++ < maxSteps
        ) {
            if (!loadPreviousPage()) break
        }
    }

    /** The window must be a CONTIGUOUS slice of [expected] — order and adjacency, not set equality. */
    protected fun assertWindowIsContiguousSliceOf(
        expected: List<MessageModel>,
        actual: List<MessageModel>,
    ) {
        if (actual.isEmpty()) return
        val expectedIds = expected.map { it.id }
        val startIndex = expectedIds.indexOf(actual.first().id)
        assertTrue("window head ${actual.first().id} is not in the expected sequence", startIndex >= 0)
        assertTrue(
            "window of ${actual.size} starting at $startIndex overruns the expected sequence",
            startIndex + actual.size <= expectedIds.size,
        )
        assertEquals(
            expectedIds.subList(startIndex, startIndex + actual.size),
            actual.map { it.id },
        )
    }

    /** Every row the user is looking at must survive the emission. The CRIT-2 assertion. */
    protected fun assertViewportRetained(viewport: List<MessageModel>, actual: List<MessageModel>) {
        val actualIds = actual.map { it.id }.toSet()
        val dropped = viewport.map { it.id }.filterNot { it in actualIds }
        assertTrue("viewport rows dropped from the window: $dropped", dropped.isEmpty())
    }

    /**
     * "This emission issues no scroll command." `PreservePosition` is the ONLY scrollAction whose
     * Fragment branch body issues none (it just calls `updateBottomFloatingButton()`); `null` is
     * NOT acceptable, because the null branch auto-snaps to the bottom and fires a read receipt.
     */
    protected fun assertPreservePositionOnly(behavior: ChatMessageListBehavior) {
        assertTrue(
            "expected PreservePosition, was ${behavior.scrollAction}",
            behavior.scrollAction is ScrollAction.PreservePosition,
        )
    }

    /**
     * Suspends until the controller emits a behavior other than [previous].
     *
     * Observer emissions are produced by a `flowOn(Dispatchers.IO)` chain, so they are NOT on the
     * test scheduler and cannot be reached with `advanceUntilIdle()`. Deliberately without
     * `withTimeout`: that would run on virtual time, which `runTest` fast-forwards while the real
     * IO hop is still in flight. A missing emission fails via `runTest`'s own timeout.
     */
    protected suspend fun ChatNormalPaginationController.awaitNextBehavior(
        previous: ChatMessageListBehavior?,
    ): ChatMessageListBehavior = chatMessagesStateFlow.first { it !== previous && it != null }!!

    /** Same contract as [awaitNextBehavior], for when the interesting emission is not the next one. */
    protected suspend fun ChatNormalPaginationController.awaitBehaviorWhere(
        predicate: (ChatMessageListBehavior) -> Boolean,
    ): ChatMessageListBehavior = chatMessagesStateFlow.first { it != null && predicate(it) }!!

    companion object {
        const val PEER_ID = "peer"
        const val MY_ID = "me"
    }
}
