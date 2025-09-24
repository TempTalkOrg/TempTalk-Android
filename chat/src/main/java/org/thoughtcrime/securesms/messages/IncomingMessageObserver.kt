package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.messages.ChatDataMessageFlag
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job

/**
 * The application-level manager of our websocket connection.
 *
 *
 * This class is responsible for opening/closing the websocket based on the app's state and observing new inbound messages received on the websocket.
 */
@Singleton
class IncomingMessageObserver @Inject constructor(
    private val incomingEnvelopMessageProcessor: IncomingEnvelopMessageProcessor,
    private val incomingConversationMessageProcessor: IncomingConversationMessageProcessor,
    private val appWebSocketHelper: AppWebSocketHelper
) {
    private var started = false
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageReceivingJob: Job? = null

    fun start() {
        if (started) return
        started = true
        messageReceivingJob = coroutineScope.launch {
            startReceivingMessage()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        messageReceivingJob?.cancel()
        messageReceivingJob = null
    }

    private suspend fun startReceivingMessage() {
        while (started) {
            try {
                val result = appWebSocketHelper.readOrEmptyFromChatDataWebSocket()
                val message = result?.second
                if (message != null) {
                    if (message is Envelope) {
                        incomingEnvelopMessageProcessor.inComeMessage(message, result.first)
                    } else if (message is ConversationPreviewWrapper) {
                        incomingConversationMessageProcessor.income(message, result.first)
                    } else if (message == ChatDataMessageFlag.END_CONVERSATION_PREVIEW_MGS) {
                        incomingConversationMessageProcessor.endReceive(result.first)
                    } else {
                        L.w { "Unknown message type: $message" }
                    }
                } else {
                    L.w { "Empty message!" }
                }
            } catch (e: Exception) {
                if (started) {
                    L.w { "Error receiving message: ${e.message}" }
                }
            }
        }
    }
}
