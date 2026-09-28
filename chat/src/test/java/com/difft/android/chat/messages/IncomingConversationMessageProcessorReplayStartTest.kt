package com.difft.android.chat.messages

import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationPreview
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope

/**
 * `queue/conversation/empty` is the first end-marker of every connection and always precedes the
 * backlog envelopes, so `endReceive` is where the prior-conversation window opens: the reporter is
 * told exactly once per connection, after the cached previews are persisted and acknowledged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IncomingConversationMessageProcessorReplayStartTest {

    private companion object {
        const val ROOM = "+10000000021"
        const val REQUEST_ID = 4242L
        const val PREVIEW_TAG = "conversation-preview"
    }

    private val envelopToMessageProcessor: EnvelopToMessageProcessor = mockk(relaxed = true)
    private val priorConversationReporter: PriorConversationReporter = mockk(relaxed = true)

    private val processor = IncomingConversationMessageProcessor(
        webSocket = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        envelopToMessageProcessor = envelopToMessageProcessor,
        priorConversationReporter = priorConversationReporter,
    )

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun preview(): ConversationPreview = ConversationPreview.newBuilder()
        .setConversationId(ConversationId.newBuilder().setNumber(ROOM).build())
        .setLastestMsg(
            Envelope.newBuilder().setSource(ROOM).setTimestamp(100L).setSystemShowTimestamp(100L).build()
        )
        .build()

    @Test
    fun `endReceive reports the open chat after the cached previews are processed`() = runTest {
        coEvery { envelopToMessageProcessor.process(any(), PREVIEW_TAG) } returns
            EnvelopeProcessResult.Success(null)
        processor.income(ConversationPreviewWrapper(preview()), REQUEST_ID)

        processor.endReceive(REQUEST_ID + 1)

        coVerifyOrder {
            envelopToMessageProcessor.process(any(), PREVIEW_TAG)
            priorConversationReporter.onReplayStarted()
        }
        verify(exactly = 1) { priorConversationReporter.onReplayStarted() }
    }

    @Test
    fun `endReceive reports the open chat even with no cached previews`() = runTest {
        processor.endReceive(REQUEST_ID)

        verify(exactly = 1) { priorConversationReporter.onReplayStarted() }
    }
}
