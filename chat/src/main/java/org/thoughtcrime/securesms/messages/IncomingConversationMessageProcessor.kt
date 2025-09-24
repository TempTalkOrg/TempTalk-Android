package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import com.difft.android.websocket.util.copyWithMsgExtraConversationId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingConversationMessageProcessor @Inject constructor(
    private val webSocket: AppWebSocketHelper,
    private val dbMessageStore: DBMessageStore,
    private val dbRoomStore: DBRoomStore,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
) {
    private val cache = HashMap<Long, ConversationPreviewWrapper>()
    suspend fun income(conversationPreviewWrapper: ConversationPreviewWrapper, requestId: Long) {
        L.i { "[Message] income conversationPreview conversation id: ${conversationPreviewWrapper.conversationPreview?.conversationId?.number}" }
        cache[requestId] = conversationPreviewWrapper
        sendAck(requestId)
    }

    private fun sendAck(requestId: Long) {
        kotlin.runCatching { webSocket.sendAckToChatDataWebSocketWithoutLog(requestId) }.onFailure {
            L.e { "[Message] sendAck exception -> ${it.stackTraceToString()}" }
        }.onSuccess {
            L.i { "[Message] sendAck for requestId $requestId success" }
        }
    }

    suspend fun endReceive(requestId: Long) {
        L.i { "[Message] endReceive request id: $requestId" }
        cache.onEach { (requestId, conversationPreviewWrapper) ->
            val conversationPreview = conversationPreviewWrapper.conversationPreview ?: return@onEach
            val forWhat = if (conversationPreview.conversationId.hasGroupId()) {
                For.Group(
                    conversationPreview.conversationId.groupId.toByteArray().transformGroupIdFromServerToLocal()
                )
            } else For.Account(conversationPreview.conversationId.number)
            val latestMsg: SignalServiceProtos.Envelope = conversationPreview.lastestMsg.copyWithMsgExtraConversationId(forWhat)
            runCatching {
                envelopToMessageProcessor.process(latestMsg, "conversation-preview")?.message?.let {
                    dbMessageStore.putWhenNonExist(it)
                }  //ignore if crashed in saving message, as it's not a big deal
            }
            if (conversationPreview.hasReadPosition()) {
                dbRoomStore.updateMessageReadPosition(forWhat, conversationPreview.readPosition.maxServerTime)
                L.i { "[Message] endReceive save read position ${conversationPreview.readPosition}" }
            }

        }
        cache.clear()
        sendAck(requestId)
    }
}