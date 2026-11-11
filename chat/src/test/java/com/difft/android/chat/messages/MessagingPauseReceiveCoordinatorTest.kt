package com.difft.android.chat.messages

import com.difft.android.chat.PendingMessageHelper
import com.difft.android.chat.websocket.WebSocketManager
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.inject.Provider


@OptIn(ExperimentalCoroutinesApi::class)
class MessagingPauseReceiveCoordinatorTest {

    private val messaging = TestMessagingPause()
    private val webSocketManager = mockk<WebSocketManager>(relaxed = true)
    private val pendingMessageHelper = mockk<PendingMessageHelper>(relaxed = true)
    private val pendingMessageProcessor = mockk<PendingMessageProcessor>(relaxed = true)
    private val failedMessageProcessor = mockk<FailedMessageProcessor>(relaxed = true)

    private fun coordinator(scope: CoroutineScope) = MessagingPauseReceiveCoordinator(
        messagingGate = messaging.gate,
        scope = scope,
        webSocketManager = Provider { webSocketManager },
        pendingMessageHelper = Provider { pendingMessageHelper },
        pendingMessageProcessor = Provider { pendingMessageProcessor },
        failedMessageProcessor = Provider { failedMessageProcessor },
    )

    @Test
    fun `loading touches nothing, the first opening resumes the socket and runs a deferred pull`() = runTest {
        coordinator(unconfined()).start()
        verify(exactly = 0) { webSocketManager.pause() }
        verify(exactly = 0) { webSocketManager.resume() }

        messaging.open()

        verify(exactly = 1) { webSocketManager.resume() }
        verify(exactly = 1) { pendingMessageHelper.runDeferredPull(any()) }
        verify(exactly = 0) { failedMessageProcessor.triggerProcess() }
    }

    @Test
    fun `a pause closes the socket, its end resumes it and re-triggers the drains`() = runTest {
        coordinator(unconfined()).start()
        messaging.open()

        messaging.pauseMessaging()
        verify(exactly = 1) { webSocketManager.pause() }

        messaging.resumeMessaging()
        verifyOrder {
            webSocketManager.resume()
            webSocketManager.pause()
            webSocketManager.resume()
        }
        verify(exactly = 2) { pendingMessageHelper.runDeferredPull(any()) }
        verify(exactly = 1) { pendingMessageProcessor.triggerProcess() }
        verify(exactly = 1) { failedMessageProcessor.triggerProcess() }
    }

    @Test
    fun `start is idempotent`() = runTest {
        val coordinator = coordinator(unconfined())
        coordinator.start()
        coordinator.start()

        messaging.open()

        verify(exactly = 1) { webSocketManager.resume() }
    }

    
    private fun TestScope.unconfined() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
}
