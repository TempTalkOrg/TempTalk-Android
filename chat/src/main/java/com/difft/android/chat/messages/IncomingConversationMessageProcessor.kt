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

    
    suspend fun endReceive(requestId: Long) {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "ws-preview-end")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=ws-preview-end count=1" }
            return
        }
        admission.use {
            sendAck(requestId)
            
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
        
        
        
        
        
        
        
        
        
        
        
        
        try {
            when (val res = envelopToMessageProcessor.process(latestMsg, "conversation-preview", admission)) {
                is EnvelopeProcessResult.Success -> {
                    
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
                    
                    
                }
                
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