package com.difft.android.chat.messages

import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import com.difft.android.websocket.api.AppWebSocketHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import difft.android.messageserialization.For
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationPreview
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReadPosition
import kotlin.time.Duration.Companion.seconds






@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IncomingConversationMessageProcessorReplayStartTest {

    private companion object {
        const val ROOM = "+10000000021"
        const val REQUEST_ID = 4242L
        const val PREVIEW_TAG = "conversation-preview"
        const val READ_POSITION = 77L
    }

    private val envelopToMessageProcessor: EnvelopToMessageProcessor = mockk(relaxed = true)
    private val priorConversationReporter: PriorConversationReporter = mockk(relaxed = true)

    private val processor = IncomingConversationMessageProcessor(
        webSocket = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        envelopToMessageProcessor = envelopToMessageProcessor,
        priorConversationReporter = priorConversationReporter,
        messagingGate = TestMessagingPause().open().gate,
    )

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun preview(readPosition: Long? = null): ConversationPreview = ConversationPreview.newBuilder()
        .setConversationId(ConversationId.newBuilder().setNumber(ROOM).build())
        .setLastestMsg(
            Envelope.newBuilder().setSource(ROOM).setTimestamp(100L).setSystemShowTimestamp(100L).build()
        )
        .apply { readPosition?.let { setReadPosition(ReadPosition.newBuilder().setMaxServerTime(it).build()) } }
        .build()

    @Test
    fun `endReceive reports the open chat after the previews are processed`() = runTest {
        coEvery { envelopToMessageProcessor.process(any(), PREVIEW_TAG, any()) } returns
            EnvelopeProcessResult.Success(null)
        processor.income(ConversationPreviewWrapper(preview()), REQUEST_ID)

        processor.endReceive(REQUEST_ID + 1)

        coVerifyOrder {
            envelopToMessageProcessor.process(any(), PREVIEW_TAG, any())
            priorConversationReporter.onReplayStarted()
        }
        verify(exactly = 1) { priorConversationReporter.onReplayStarted() }
    }

    
    
    @Test
    fun `while paused previews are neither ACKed nor processed`() = runTest {
        val webSocket = mockk<AppWebSocketHelper>(relaxed = true)
        val paused = IncomingConversationMessageProcessor(
            webSocket = webSocket,
            dbRoomStore = mockk(relaxed = true),
            envelopToMessageProcessor = envelopToMessageProcessor,
            priorConversationReporter = priorConversationReporter,
            messagingGate = TestMessagingPause().open().pauseMessaging().gate,
        )

        paused.income(ConversationPreviewWrapper(preview()), REQUEST_ID)
        paused.endReceive(REQUEST_ID + 1)

        verify(exactly = 0) { webSocket.sendAckToChatDataWebSocketWithoutLog(any()) }
        coVerify(exactly = 0) { envelopToMessageProcessor.process(any(), any(), any()) }
        verify(exactly = 0) { priorConversationReporter.onReplayStarted() }
    }

    
    
    
    @Test
    fun `an ACKed preview is stored before the pause even when its end marker is refused`() = runTest {
        val messaging = TestMessagingPause().open()
        val webSocket = mockk<AppWebSocketHelper>(relaxed = true)
        val dbRoomStore = mockk<DBRoomStore>(relaxed = true)
        coEvery { envelopToMessageProcessor.process(any(), PREVIEW_TAG, any()) } returns
            EnvelopeProcessResult.Success(null)
        val beforePause = IncomingConversationMessageProcessor(
            webSocket = webSocket,
            dbRoomStore = dbRoomStore,
            envelopToMessageProcessor = envelopToMessageProcessor,
            priorConversationReporter = priorConversationReporter,
            messagingGate = messaging.gate,
        )

        beforePause.income(ConversationPreviewWrapper(preview(readPosition = READ_POSITION)), REQUEST_ID)
        messaging.pauseMessaging()

        assertTrue(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 1.seconds))
        
        verify(exactly = 1) { webSocket.sendAckToChatDataWebSocketWithoutLog(REQUEST_ID) }
        coVerify(exactly = 1) { envelopToMessageProcessor.process(any(), PREVIEW_TAG, any()) }
        coVerify(exactly = 1) { dbRoomStore.updateMessageReadPosition(For.Account(ROOM), READ_POSITION) }

        beforePause.endReceive(REQUEST_ID + 1)

        verify(exactly = 0) { webSocket.sendAckToChatDataWebSocketWithoutLog(REQUEST_ID + 1) }
        verify(exactly = 0) { priorConversationReporter.onReplayStarted() }
        coVerify(exactly = 1) { envelopToMessageProcessor.process(any(), PREVIEW_TAG, any()) }
    }

    @Test
    fun `endReceive reports the open chat even with no cached previews`() = runTest {
        processor.endReceive(REQUEST_ID)

        verify(exactly = 1) { priorConversationReporter.onReplayStarted() }
    }
}
