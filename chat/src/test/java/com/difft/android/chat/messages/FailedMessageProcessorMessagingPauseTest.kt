package com.difft.android.chat.messages

import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDB
import org.junit.Test


class FailedMessageProcessorMessagingPauseTest {

    private val messaging = TestMessagingPause().open()
    private val wcdb = mockk<WCDB>(relaxed = true)
    private val processor = FailedMessageProcessor(
        envelopToMessageProcessor = mockk(relaxed = true),
        wcdb = wcdb,
        messagingGate = messaging.gate,
    )

    @Test
    fun `a tick while paused reads and writes nothing`() = runBlocking {
        messaging.pauseMessaging()

        processor.processAdmittedTick()

        verify(exactly = 0) { wcdb.failedMessage }
    }

    @Test
    fun `a tick while open runs`() = runBlocking {
        processor.processAdmittedTick()

        verify(atLeast = 1) { wcdb.failedMessage }
    }
}
