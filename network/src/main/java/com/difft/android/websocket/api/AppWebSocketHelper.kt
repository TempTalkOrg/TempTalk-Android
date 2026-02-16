package com.difft.android.websocket.api

import com.difft.android.base.log.lumberjack.L
import io.reactivex.rxjava3.core.Single
import com.difft.android.websocket.api.messages.ChatDataMessageFlag
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import com.difft.android.websocket.api.util.EnvelopDeserializer
import com.difft.android.websocket.api.websocket.WebSocketUnavailableException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationPreview
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage
import com.difft.android.websocket.internal.websocket.WebsocketResponse
import org.whispersystems.signalservice.internal.websocket.webSocketResponseMessage
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provide a general interface to the WebSocket for making requests and reading messages sent by the server.
 * Where appropriate, it will handle retrying failed unidentified requests on the regular WebSocket.
 */
@Singleton
class AppWebSocketHelper
@Inject constructor(
    // chat socket
    @param:Named("chat-data")
    val chatDataWebSocketConnection: WebSocketConnection,
) {
    fun sendChatMessage(
        requestMessage: WebSocketRequestMessage,
    ): Single<WebsocketResponse> {
        return try {
            chatDataWebSocketConnection.sendRequestAwaitResponse(requestMessage)
        } catch (e: IOException) {
            Single.error(e)
        }
    }

    /**
     *
     *
     * A blocking call that reads a message off the pipe. When this call returns, the message has been
     * acknowledged and will not be retransmitted. This will return [Optional.empty] when an
     * empty response is hit, which indicates the WebSocket is empty.
     *
     *
     * Important: The empty response will only be hit once for each connection. That means if you get
     * an empty response and call readOrEmpty() again on the same instance, you will not get an empty
     * response, and instead will block until you get an actual message. This will, however, reset if
     * connection breaks (if, for instance, you lose and regain network).
     *
     * @return The message read (same as the message sent through the callback).
     */
    @Throws(WebSocketUnavailableException::class, IOException::class)
    fun readOrEmptyFromChatDataWebSocket(): Pair<Long, Any?>? {
        while (true) {
            val request = chatDataWebSocketConnection.readRequest()
            try {
                if (isSignalServiceEnvelope(request)) {
                    L.i { "[AppWebSocketHelper] read message envelope:" + request.requestId }
                    val envelope: Envelope? = parseSignalServiceEnvelope(request)
                    return Pair(request.requestId, envelope)
                } else if (isSocketConversationRequest(request)) {
                    L.i { "[AppWebSocketHelper] read message conversation:" + request.requestId }
                    val conversationPreview: ConversationPreview? = readConversationMsgInfo(request)
                    return Pair(
                        request.requestId,
                        ConversationPreviewWrapper(conversationPreview)
                    )
                } else if (isSocketConversationEmptyRequest(request)) {
                    L.i { "[AppWebSocketHelper] read message conversation empty:" + request.requestId }
                    sendAckToChatDataWebSocket(request.requestId);
                    return Pair(request.requestId, ChatDataMessageFlag.END_CONVERSATION_PREVIEW_MGS)
                } else {
                    L.i { "[AppWebSocketHelper] read message other type:" + request.path }
                    sendAckToChatDataWebSocket(request.requestId);
                    return null
                }
            } catch (e: Exception) {
                L.w(e) { "[AppWebSocketHelper] read message exception:" }
            }
        }
    }

    private fun readConversationMsgInfo(request: WebSocketRequestMessage): ConversationPreview? {
        return ConversationPreview.parseFrom(request.body.toByteArray())
    }


    private fun parseSignalServiceEnvelope(request: WebSocketRequestMessage): Envelope? {
        val input = request.body.toByteArray()
        val envelope: Envelope? = EnvelopDeserializer.deserializeFrom(input)
        if (envelope == null) {
            L.w { "[AppWebSocketHelper] read message envelope is null:" + request.requestId }
            sendAckToChatDataWebSocket(request.requestId);
        } else {
            L.i { "[AppWebSocketHelper] parse envelope success: ${request.requestId} ${envelope.timestamp}" }
        }
        return envelope
    }


    @Throws(IOException::class)
    fun sendAckToChatDataWebSocket(requestId: Long) {
        sendAckToChatDataWebSocketWithoutLog(requestId)
        L.i { "[AppWebSocketHelper] send ack -> requestId:$requestId" }
    }

    @Throws(IOException::class)
    fun sendAckToChatDataWebSocketWithoutLog(requestId: Long?) {
        val response = webSocketResponseMessage {
            this.requestId = requestId!!
            status = 200
            message = "OK"
        }
        chatDataWebSocketConnection.sendResponse(response)
    }

    companion object {

        private fun isSignalServiceEnvelope(message: WebSocketRequestMessage): Boolean {
            return "PUT" == message.verb && "/api/v1/message" == message.path
        }

        private fun isSocketEmptyRequest(message: WebSocketRequestMessage): Boolean {
            return "PUT" == message.verb && "/api/v1/queue/empty" == message.path
        }

        private fun isSocketConversationEmptyRequest(message: WebSocketRequestMessage): Boolean {
            return "PUT" == message.verb && ("/api/v1/conversation/empty" == message.path || "/api/v1/queue/conversation/empty" == message.path)
        }

        private fun isSocketConversationRequest(message: WebSocketRequestMessage): Boolean {
            return "PUT" == message.verb && "/api/v1/conversation" == message.path
        }
    }
}
