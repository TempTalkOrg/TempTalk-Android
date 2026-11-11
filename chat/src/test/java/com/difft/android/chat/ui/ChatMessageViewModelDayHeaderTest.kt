package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isNotifyStyleMessage
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ScreenShot
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.MessageModel
import org.difft.app.database.screenShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import util.TimeFormatter













@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelDayHeaderTest : ChatMessageViewModelTestBase() {

    
    @Test
    fun `single-pass day headers match the rescan algorithm on every list shape`() = runTest {
        val shapes = listOf<Pair<String, List<MessageModel>>>(
            "all plain" to listOf(row(DAY_1), row(DAY_1), row(DAY_2), row(DAY_2), row(DAY_3)),
            "leading notify" to listOf(notifyRow(DAY_1), row(DAY_1), row(DAY_2)),
            "notify run in the middle" to listOf(
                row(DAY_1), notifyRow(DAY_1), notifyRow(DAY_2), notifyRow(DAY_2), row(DAY_2), row(DAY_3),
            ),
            "all notify" to listOf(notifyRow(DAY_1), notifyRow(DAY_2), notifyRow(DAY_3)),
            "single row" to listOf(row(DAY_1)),
            "empty" to emptyList(),
        )
        val anchors = listOf<Pair<String, MessageModel?>>(
            "no anchor" to null,
            "plain anchor on the same day as the first row" to row(DAY_1),
            "notify anchor" to notifyRow(DAY_1),
        )

        shapes.forEach { (shapeName, rows) ->
            anchors.forEach { (anchorName, anchor) ->
                assertDayHeadersMatchReference("$shapeName / $anchorName", rows, anchor)
            }
        }
    }

    
    
    
    @Test
    fun `single-pass day headers match the rescan algorithm on a long notify run`() = runTest {
        val rows = List(NOTIFY_RUN) { notifyRow(DAY_1) } +
            List(TAIL_ROWS) { row(DAY_2) } +
            List(TAIL_ROWS) { row(DAY_3) }

        assertDayHeadersMatchReference("long notify run", rows, anchor = null)
    }

    
    
    @Test
    fun `the same window emitted twice produces the same day headers`() = runTest {
        val rows = listOf(row(DAY_1), notifyRow(DAY_1), row(DAY_2), row(DAY_2))

        val first = showDayTimeFromPipeline(rows, anchor = null)
        val second = showDayTimeFromPipeline(rows, anchor = null)

        assertEquals(first, second)
    }

    
    
    
    @Test
    fun `notify-style detection ignores the display flags the pass writes`() {
        val plain = TextChatMessage().apply {
            showDayTime = true
            showName = true
            showTime = true
        }
        val screenshot = TextChatMessage().apply {
            isScreenShotMessage = true
            showDayTime = false
            showName = false
            showTime = false
        }

        assertFalse(plain.isNotifyStyleMessage())
        assertTrue(screenshot.isNotifyStyleMessage())
    }

    

    private suspend fun assertDayHeadersMatchReference(
        label: String,
        rows: List<MessageModel>,
        anchor: MessageModel?,
    ) {
        val expected = referenceShowDayTime(
            rows.map { asChatMessage(it) },
            anchor = anchor?.let { asChatMessage(it) },
        )
        val actual = showDayTimeFromPipeline(rows, anchor)
        assertEquals(label, expected, actual)
    }

    
    private suspend fun showDayTimeFromPipeline(
        rows: List<MessageModel>,
        anchor: MessageModel?,
    ): List<Boolean> {
        
        
        
        behaviorFlow.value = null
        val vm = viewModel(For.Account(PEER_ID))
        var result: List<Boolean> = emptyList()
        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = rows,
                anchorMessageBefore = anchor,
                
                
                hasReachedHistoryStart = false,
                updateTimestamp = System.nanoTime(),
            )
            pumpMainLooper(times = PUMP_TIMES, stepMs = PUMP_STEP_MS)
            val state = awaitItem()
            assertEquals("the emission under test", rows.map { it.id }, state.chatMessages.map { it.id })
            result = state.chatMessages.map { it.showDayTime }
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    



    private fun referenceShowDayTime(rows: List<ChatMessage>, anchor: ChatMessage?): List<Boolean> =
        rows.mapIndexed { index, message ->
            if (message.isNotifyStyleMessage()) {
                false
            } else {
                val previousNonNotify = (
                    if (index > 0) rows.subList(0, index).lastOrNull { !it.isNotifyStyleMessage() } else null
                    ) ?: anchor?.takeIf { !it.isNotifyStyleMessage() }
                !TimeFormatter.isSameDay(message.timeStamp, previousNonNotify?.timeStamp ?: 0L)
            }
        }

    



    private fun asChatMessage(model: MessageModel): ChatMessage = TextChatMessage().apply {
        id = model.id
        timeStamp = model.timeStamp
        systemShowTimestamp = model.systemShowTimestamp
        isScreenShotMessage = model.screenShot() != null
    }

    




    private fun row(dayMillis: Long): MessageModel {
        sequence += 1
        return MessageModel().apply {
            id = "m$sequence"
            type = MessageModel.TYPE_TEXT
            timeStamp = dayMillis + sequence
            systemShowTimestamp = dayMillis + sequence
        }
    }

    
    private fun notifyRow(dayMillis: Long): MessageModel =
        row(dayMillis).also { every { it.screenShot() } returns ScreenShot(null) }

    private var sequence = 0L

    private companion object {
        const val PEER_ID = "peer-uid"
        const val DAY_1 = 1_700_000_000_000L
        const val DAY_2 = DAY_1 + 24 * 60 * 60 * 1000L
        const val DAY_3 = DAY_2 + 24 * 60 * 60 * 1000L
        const val NOTIFY_RUN = 120
        const val TAIL_ROWS = 30
        const val PUMP_TIMES = 40
        const val PUMP_STEP_MS = 5L
    }
}
