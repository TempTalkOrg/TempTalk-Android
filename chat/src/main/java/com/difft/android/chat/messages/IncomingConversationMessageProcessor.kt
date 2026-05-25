package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import com.difft.android.websocket.util.copyWithMsgExtraConversationId
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingConversationMessageProcessor @Inject constructor(
    private val webSocket: AppWebSocketHelper,
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
            // Conversation-preview is best-effort: persistence happens inside
            // `process()` on Success. We still report PermanentFailure here so
            // the per-envelope Crashlytics signal isn't lost on this path —
            // `EnvelopToMessageProcessor.process` deliberately defers logging
            // and recordException to the caller via `reportPermanentDrop`.
            // TransientFailure is left alone because the regular incoming
            // path will re-deliver and own the retry.
            //
            // Wrapped in try/catch (CancellationException rethrown) so an
            // unforeseen throw from `process()` — e.g. OOM / SOF — does not
            // skip the rest of the loop body, leaving `cache` dirty and the
            // final `sendAck(requestId)` unsent. This restores the
            // best-effort guarantee the pre-v2 `runCatching` provided.
            try {
                when (val res = envelopToMessageProcessor.process(latestMsg, "conversation-preview")) {
                    is EnvelopeProcessResult.Success -> {
                        // No-op — persistence + dedup already done inside process().
                    }
                    is EnvelopeProcessResult.PermanentFailure -> {
                        reportPermanentDrop(
                            res.reason,
                            res.cause,
                            latestMsg.timestamp,
                            tag = "conversation-preview",
                        )
                    }
                    is EnvelopeProcessResult.TransientFailure -> {
                        // Best-effort: same envelope arrives via the regular
                        // incoming path and is retried there.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                L.w { "[Message] conversation-preview process failed (best-effort, continuing): ${e.stackTraceToString()}" }
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