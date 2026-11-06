package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
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
    private val priorConversationReporter: PriorConversationReporter,
    private val messagingGate: MessagingGate,
) {
    /**
     * ACKs one preview and stores its latest message and read position under one ticket (#1382).
     * An ACKed preview is consumed on the server, so it is stored at once rather than held for the
     * end marker: the marker may never be read (the socket closes) or may be refused (the pause
     * began), and nothing held in memory could then reach the migration snapshot. A refused preview
     * is neither ACKed nor stored and stays queued on the server.
     */
    suspend fun income(conversationPreviewWrapper: ConversationPreviewWrapper, requestId: Long) {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "ws-preview")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=ws-preview count=1" }
            return
        }
        admission.use {
            L.i { "[Message] income conversationPreview conversation id: ${conversationPreviewWrapper.conversationPreview?.conversationId?.number}" }
            sendAck(requestId)
            storePreview(conversationPreviewWrapper, it)
        }
    }

    private fun sendAck(requestId: Long) {
        kotlin.runCatching { webSocket.sendAckToChatDataWebSocketWithoutLog(requestId) }.onFailure {
            L.e { "[Message] sendAck exception -> ${it.stackTraceToString()}" }
        }.onSuccess {
            L.d { "[Message] sendAck for requestId $requestId success" }
        }
    }

    /** ACKs the end marker and opens the prior-conversation window, only while messaging is open (#1382). */
    suspend fun endReceive(requestId: Long) {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "ws-preview-end")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=ws-preview-end count=1" }
            return
        }
        admission.use {
            sendAck(requestId)
            // Report the open chat so the server delivers its backlog first.
            priorConversationReporter.onReplayStarted()
        }
    }

    private suspend fun storePreview(conversationPreviewWrapper: ConversationPreviewWrapper, admission: MessagingTicket) {
        val conversationPreview = conversationPreviewWrapper.conversationPreview ?: return
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
        // skip the read-position write below. This restores the
        // best-effort guarantee the pre-v2 `runCatching` provided.
        try {
            when (val res = envelopToMessageProcessor.process(latestMsg, "conversation-preview", admission)) {
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
                // Unreachable while income holds its ticket.
                EnvelopeProcessResult.Paused -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            L.w { "[Message] conversation-preview process failed (best-effort, continuing): ${e.stackTraceToString()}" }
        }
        if (conversationPreview.hasReadPosition()) {
            dbRoomStore.updateMessageReadPosition(forWhat, conversationPreview.readPosition.maxServerTime)
            L.i { "[Message] income save read position ${conversationPreview.readPosition}" }
        }
    }
}