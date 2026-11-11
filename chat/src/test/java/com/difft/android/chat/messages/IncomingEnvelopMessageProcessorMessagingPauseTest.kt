package com.difft.android.chat.messages

import android.os.SystemClock
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.AppWebSocketHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds


class IncomingEnvelopMessageProcessorMessagingPauseTest {

    init {
        
        io.mockk.mockkStatic(SystemClock::class)
        io.mockk.every { SystemClock.elapsedRealtime() } returns 0L
    }

    @org.junit.After
    fun tearDown() = io.mockk.unmockkStatic(SystemClock::class)

    private val messaging = TestMessagingPause().open()
    private val webSocket = mockk<AppWebSocketHelper>(relaxed = true)
    private val envelopToMessageProcessor = mockk<EnvelopToMessageProcessor>()
    private val processor = IncomingEnvelopMessageProcessor(
        context = mockk(relaxed = true),
        webSocket = webSocket,
        envelopToMessageProcessor = envelopToMessageProcessor,
        asyncMessageJobsManager = mockk(relaxed = true),
        pendingMessageProcessor = mockk(relaxed = true),
        failedMessageProcessor = mockk(relaxed = true),
        messageNotificationUtil = mockk(relaxed = true),
        wakeLock = mockk(relaxed = true),
        powerSampler = mockk(relaxed = true),
        messagingGate = messaging.gate,
    )

    private fun queued(envelope: Envelope, requestId: Long, seq: Long) =
        IncomingEnvelopMessageProcessor.QueuedEnvelope(envelope, requestId, queuedAt = 0L, seq = seq)

    private val batch = listOf(
        queued(Envelope.newBuilder().setTimestamp(1L).setSystemShowTimestamp(1L).build(), 11L, seq = 1L),
        queued(Envelope.newBuilder().setTimestamp(2L).setSystemShowTimestamp(2L).build(), 12L, seq = 2L),
    )

    @Test
    fun `T9 a batch arriving while paused is neither ACKed nor processed`() = runBlocking {
        messaging.pauseMessaging()

        processor.doProcessBatch(batch)

        verify(exactly = 0) { webSocket.sendAckToChatDataWebSocketWithoutLog(any()) }
        coVerify(exactly = 0) { envelopToMessageProcessor.process(any(), any(), any()) }
    }

    @Test
    fun `a batch while messaging is still loading is neither ACKed nor processed`() = runBlocking {
        val loading = TestMessagingPause()
        val closed = IncomingEnvelopMessageProcessor(
            mockk(relaxed = true), webSocket, envelopToMessageProcessor, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), loading.gate,
        )

        closed.doProcessBatch(batch)

        verify(exactly = 0) { webSocket.sendAckToChatDataWebSocketWithoutLog(any()) }
    }

    @Test
    fun `T8 a batch admitted before the pause is ACKed, fully processed and awaited by the close`() = runBlocking {
        val inProcess = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val admissions = mutableListOf<MessagingTicket>()
        coEvery { envelopToMessageProcessor.process(any(), "message", any()) } coAnswers {
            admissions += thirdArg<MessagingTicket>()
            inProcess.complete(Unit)
            release.await()
            EnvelopeProcessResult.Success(null)
        }

        val running = launch(Dispatchers.IO) { processor.doProcessBatch(batch) }
        withTimeout(5.seconds) { inProcess.await() }
        messaging.pauseMessaging()
        val idle = async(Dispatchers.IO) { messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds) }

        Thread.sleep(SETTLE_MS)
        assertFalse(idle.isCompleted, "the close must wait for the admitted batch")
        release.complete(Unit)

        assertTrue(idle.await())
        running.join()
        verify(exactly = 1) { webSocket.sendAckToChatDataWebSocketWithoutLog(11L) }
        verify(exactly = 1) { webSocket.sendAckToChatDataWebSocketWithoutLog(12L) }
        coVerify(exactly = 2) { envelopToMessageProcessor.process(any(), "message", any()) }
        
        assertTrue(admissions.all { it === admissions.first() })
    }

    private companion object {
        const val SETTLE_MS = 200L
    }
}
