package com.difft.android.websocket.api

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
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





@Singleton
class AppWebSocketHelper
@Inject constructor(
    
    @param:Named("chat-data")
    val chatDataWebSocketConnection: WebSocketConnection,
    private val messagingGate: MessagingGate,
) {
    @Throws(IOException::class)
    suspend fun sendChatMessage(
        requestMessage: WebSocketRequestMessage,
    ): WebsocketResponse {
        return chatDataWebSocketConnection.sendRequestAwaitResponse(requestMessage)
    }

    














    @Throws(WebSocketUnavailableException::class, IOException::class)
    fun readOrEmptyFromChatDataWebSocket(): Pair<Long, Any?>? {
        while (true) {
            val request = chatDataWebSocketConnection.readRequest()
            try {
                if (isSignalServiceEnvelope(request)) {
                    L.d { "[AppWebSocketHelper] read message envelope:" + request.requestId }
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
                    if (!ackIfAdmitted(request.requestId, "conversation-empty")) return null
                    L.i { "[AppWebSocketHelper] conversation previews done, acked requestId=${request.requestId}" }
                    return Pair(request.requestId, ChatDataMessageFlag.END_CONVERSATION_PREVIEW_MGS)
                } else if (isSocketEmptyRequest(request)) {
                    if (!ackIfAdmitted(request.requestId, "queue-empty")) return null
                    L.i { "[AppWebSocketHelper] queue empty, acked requestId=${request.requestId}" }
                    return Pair(request.requestId, ChatDataMessageFlag.END_QUEUE)
                } else {
                    L.i { "[AppWebSocketHelper] read message other type:" + request.path }
                    ackIfAdmitted(request.requestId, "other")
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
            ackIfAdmitted(request.requestId, "unparseable")
        } else {
            L.d { "[AppWebSocketHelper] parse envelope success: ${request.requestId} ${envelope.timestamp}" }
        }
        return envelope
    }


    





    @Throws(IOException::class)
    private fun ackIfAdmitted(requestId: Long, what: String): Boolean {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "ws-$what")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=ws-$what count=1" }
            return false
        }
        admission.use { sendAckToChatDataWebSocket(requestId) }
        return true
    }

    @Throws(IOException::class)
    fun sendAckToChatDataWebSocket(requestId: Long) {
        sendAckToChatDataWebSocketWithoutLog(requestId)
        L.d { "[AppWebSocketHelper] send ack -> requestId:$requestId" }
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
