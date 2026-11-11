package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.messages.DropReason
import com.difft.android.chat.messages.EnvelopToMessageProcessor
import com.difft.android.chat.messages.EnvelopeProcessResult
import com.difft.android.chat.messages.FailedMessageProcessor
import com.difft.android.chat.messages.reportPermanentDrop
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.PendingMessage
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.models.DBFailedMessageModel
import org.difft.app.database.wcdb
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.envelope
import org.whispersystems.signalservice.internal.push.msgExtra
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject


@Suppress("BlockingWcdbInSuspend")
class PendingMessageHelper @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
    private val healthCounters: KeepAliveHealthCounters,
    private val messagingGate: MessagingGate,
) {

    
    enum class PullTrigger { FCM, OTHER }

    suspend fun obtainPendingMessageAndSave(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext withTimeout(10000L) {
                processPendingMessages()
            }
        } catch (e: Exception) {
            L.e { "[Message][PendingMessageHelper] process failed: ${e.stackTraceToString()}" }
            return@withContext false
        }
    }

    





    fun schedulePendingMessageWork(trigger: PullTrigger = PullTrigger.OTHER, scope: CoroutineScope = appScope) {
        startPull(trigger, scope)
    }

    
    private fun startPull(trigger: PullTrigger, scope: CoroutineScope): Boolean {
        if (!messagingGate.isOpen) {
            deferPull(trigger, scope)
            return true
        }
        if (!isProcessing.compareAndSet(false, true)) {
            L.i { "[Message][PendingMessageHelper] Already processing pending messages, skipping duplicate request" }
            return false
        }

        scope.launch(Dispatchers.IO) {
            var counted = false
            try {
                val startTime = System.currentTimeMillis()
                L.i { "[Message][PendingMessageHelper] Starting direct processing" }

                val success = obtainPendingMessageAndSave()
                
                ensureActive()
                counted = recordFcmPull(trigger, success)

                val duration = System.currentTimeMillis() - startTime
                if (success) {
                    L.i { "[Message][PendingMessageHelper] Direct processing completed successfully in ${duration}ms" }
                } else {
                    L.w { "[Message][PendingMessageHelper] Direct processing partial success in ${duration}ms, WebSocket will compensate" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!counted) recordFcmPull(trigger, false)
                L.e { "[Message][PendingMessageHelper] Direct processing failed: ${e.stackTraceToString()}" }
            } finally {
                isProcessing.set(false)
                
                if (scope.isActive && messagingGate.isOpen && deferredPull.get() != null) runDeferredPull(scope)
            }
        }
        return true
    }

    
    fun runDeferredPull(scope: CoroutineScope = appScope) {
        while (true) {
            val trigger = deferredPull.getAndSet(null) ?: return
            L.i { "[MigrationPause] event=deferred_pull_run trigger=$trigger" }
            if (startPull(trigger, scope)) return
            
            deferredPull.getAndUpdate { if (it == PullTrigger.FCM) it else trigger }
            if (isProcessing.get()) return
        }
    }

    
    private fun deferPull(trigger: PullTrigger, scope: CoroutineScope?) {
        
        val previous = deferredPull.getAndUpdate { if (it == PullTrigger.FCM) it else trigger }
        if (previous == null) L.i { "[MigrationPause] event=pull_deferred trigger=$trigger" }
        
        if (scope != null && messagingGate.isOpen) runDeferredPull(scope)
    }

    private fun recordFcmPull(trigger: PullTrigger, success: Boolean): Boolean {
        if (trigger != PullTrigger.FCM) return false
        
        runCatching { healthCounters.onPullResult(success) }
        return true
    }

    companion object {
        private val isProcessing = AtomicBoolean(false)

        
        private val deferredPull = AtomicReference<PullTrigger?>(null)
    }

    




    private suspend fun processPendingMessages(): Boolean {
        var more = true
        while (more) {
            val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "pull")
            if (admission == null) {
                L.i { "[MigrationPause] event=receive_dropped source=pull count=0" }
                deferPull(PullTrigger.OTHER, scope = null)
                return true
            }
            more = admission.use { processPage(it) } ?: return false
        }
        return true
    }

    
    private suspend fun processPage(admission: MessagingTicket): Boolean? {
        val pendingMessageResponse = try {
            httpClient.httpService.getPendingMessage()
        } catch (e: Exception) {
            L.e { "[Message][PendingMessageHelper] obtainPendingMessage failed -> ${e.stackTraceToString()}" }
            return null
        }
        val more = pendingMessageResponse.more
        L.i { "[Message][PendingMessageHelper] obtainPendingMessage success size:${pendingMessageResponse.messages?.size} more:${more}" }

        val messages = pendingMessageResponse.messages ?: emptyList()

        
        val sortedMessages = messages.sortedBy { it.systemShowTimestamp }
        val failedEnvelopes = mutableListOf<SignalServiceProtos.Envelope>()
        val refused = mutableSetOf<PendingMessage>()

        sortedMessages.forEach { msg ->
            val envelope = buildEnvelope(msg) ?: return@forEach
            when (val processRes = envelopToMessageProcessor.process(envelope, "PendingMessageHelper", admission)) {
                is EnvelopeProcessResult.Success -> {
                    
                    runCatching {
                        wcdb.failedMessage.deleteObjects(
                            DBFailedMessageModel.timestamp.eq(envelope.timestamp)
                        )
                    }.onFailure { e ->
                        L.w { "[Message][PendingMessageHelper] dedup deleteObjects failed ts=${envelope.timestamp}: ${e.stackTraceToString()}" }
                    }
                }
                is EnvelopeProcessResult.PermanentFailure -> {
                    reportPermanentDrop(
                        processRes.reason,
                        processRes.cause,
                        envelope.timestamp,
                        tag = "PendingMessageHelper",
                    )
                }
                is EnvelopeProcessResult.TransientFailure -> {
                    failedEnvelopes.add(envelope)
                }
                
                EnvelopeProcessResult.Paused -> refused.add(msg)
            }
        }

        if (failedEnvelopes.isNotEmpty()) {
            L.w { "[Message][PendingMessageHelper] ${failedEnvelopes.size} messages failed transient, saving for retry" }
            failedMessageProcessor.saveTransient(failedEnvelopes)
            
            
            
            failedMessageProcessor.triggerProcess()
        }

        WCDBUpdateService.updatingRooms()
        
        
        
        
        
        deletePendingMessages(messages.filterNot { it in refused })
        return more
    }

    private suspend fun deletePendingMessages(messages: List<PendingMessage>) = coroutineScope {
        val batches = messages.chunked(10)
        batches.forEachIndexed { index, batch ->
            batch.map { msg ->
                async(Dispatchers.IO) {
                    try {
                        val result = httpClient.httpService.removePendingMessage(
                            msg.source.toString(),
                            msg.timestamp.toString()
                        )

                        if (result.isSuccess()) {
                            L.i { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} success" }
                        } else {
                            L.e { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} failed -> ${result.reason}" }
                        }
                    } catch (e: Exception) {
                        L.e { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} failed -> ${e.message}" }
                    }
                }
            }.awaitAll()

            if (index < batches.size - 1) {
                delay(50)
            } else {
                
                delay(1500)
                L.i { "[Message][PendingMessageHelper] Deleted all messages, waiting 1.5s for server sync" }
            }
        }
    }

    private fun buildEnvelope(message: PendingMessage): SignalServiceProtos.Envelope? {
        try {
            val envelope = envelope {
                type = SignalServiceProtos.Envelope.Type.forNumber(message.type)
                message.relay?.let(::relay::set)
                timestamp = message.timestamp
                message.source?.let(::source::set)
                sourceDevice = message.sourceDevice
                message.content?.let {
                    Base64.decode(it)?.let { contentData ->
                        content = ByteString.copyFrom(contentData)
                    }
                }
                systemShowTimestamp = message.systemShowTimestamp
                sequenceId = message.sequenceId
                notifySequenceId = message.notifySequenceId
                msgType = SignalServiceProtos.Envelope.MsgType.forNumber(message.msgType)
                message.conversation?.let { conversation ->
                    val conversationId1 = conversationId {
                        if (conversation.contains(":")) {
                            number = conversation.replace(globalServices.myId, "").replace(":", "")
                        } else {
                            
                            
                            groupId = ByteString.copyFrom(conversation.transformGroupIdFromLocalToServer())
                        }
                    }
                    msgExtra = msgExtra {
                        conversationId = conversationId1
                    }
                }
                message.identityKey?.let(::identityKey::set)
                message.peerContext?.let(::peerContext::set)
            }
            return envelope
        } catch (e: Exception) {
            
            
            
            
            
            
            
            
            val reason = if (e is java.io.IOException) {
                DropReason.BASE64_DECODE_FAILED
            } else {
                DropReason.BUILD_ENVELOPE_FAILED
            }
            reportPermanentDrop(
                reason,
                e,
                message.timestamp,
                tag = "PendingMessageHelper",
            )
            return null
        }
    }
}
