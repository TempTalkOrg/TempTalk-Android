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






@OptIn(ExperimentalCoroutinesApi::class)
abstract class PaginationTestBase {

    protected val source = FakeChatMessageWindowSource()
    protected val forWhat: For = For.Account(PEER_ID)

    protected fun TestScope.controller(myId: String = MY_ID): ChatNormalPaginationController =
        ChatNormalPaginationController(forWhat, source, myId).apply {
            bindCoroutineScope(backgroundScope)
        }

    

    protected fun ChatNormalPaginationController.behavior(): ChatMessageListBehavior =
        requireNotNull(chatMessagesStateFlow.value) { "no ChatMessageListBehavior has been emitted yet" }

    protected fun ChatNormalPaginationController.window(): List<MessageModel> = behavior().messageList

    protected fun ChatNormalPaginationController.windowIds(): List<String> = window().map { it.id }

    







    protected suspend fun ChatNormalPaginationController.fillWindowToCap(maxSteps: Int = 20) {
        var steps = 0
        while (window().size < ChatNormalPaginationController.MAX_MESSAGE_COUNT && steps++ < maxSteps) {
            if (!loadPreviousPage()) break
        }
    }

    








    protected suspend fun ChatNormalPaginationController.fillWindowBelowCap(maxSteps: Int = 20) {
        var steps = 0
        while (
            window().size + ChatNormalPaginationController.SCROLL_PAGE_SIZE <=
            ChatNormalPaginationController.MAX_MESSAGE_COUNT && steps++ < maxSteps
        ) {
            if (!loadPreviousPage()) break
        }
    }

    
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

    
    protected fun assertViewportRetained(viewport: List<MessageModel>, actual: List<MessageModel>) {
        val actualIds = actual.map { it.id }.toSet()
        val dropped = viewport.map { it.id }.filterNot { it in actualIds }
        assertTrue("viewport rows dropped from the window: $dropped", dropped.isEmpty())
    }

    




    protected fun assertPreservePositionOnly(behavior: ChatMessageListBehavior) {
        assertTrue(
            "expected PreservePosition, was ${behavior.scrollAction}",
            behavior.scrollAction is ScrollAction.PreservePosition,
        )
    }

    







    protected suspend fun ChatNormalPaginationController.awaitNextBehavior(
        previous: ChatMessageListBehavior?,
    ): ChatMessageListBehavior = chatMessagesStateFlow.first { it !== previous && it != null }!!

    
    protected suspend fun ChatNormalPaginationController.awaitBehaviorWhere(
        predicate: (ChatMessageListBehavior) -> Boolean,
    ): ChatMessageListBehavior = chatMessagesStateFlow.first { it != null && predicate(it) }!!

    companion object {
        const val PEER_ID = "peer"
        const val MY_ID = "me"
    }
}
