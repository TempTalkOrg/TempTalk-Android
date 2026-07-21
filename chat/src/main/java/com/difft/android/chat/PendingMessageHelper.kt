package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
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
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import javax.inject.Inject

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
class PendingMessageHelper @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
) {

    suspend fun obtainPendingMessageAndSave(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 添加超时控制，防止事务卡死
            return@withContext withTimeout(10000L) { // 10秒超时
                processPendingMessages()
            }
        } catch (e: Exception) {
            L.e { "[Message][PendingMessageHelper] process failed: ${e.stackTraceToString()}" }
            return@withContext false
        }
    }

    /**
     * 主动拉取和处理消息
     * - 依赖 WebSocket 实时同步作为主要消息通道，FCM 拉取作为补充
     * - 使用 AtomicBoolean 互斥锁防止并发执行
     */
    fun schedulePendingMessageWork() {
        // 使用 compareAndSet 原子操作获取锁，避免并发执行
        if (!isProcessing.compareAndSet(false, true)) {
            L.i { "[Message][PendingMessageHelper] Already processing pending messages, skipping duplicate request" }
            return
        }

        appScope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                L.i { "[Message][PendingMessageHelper] Starting direct processing" }

                val success = obtainPendingMessageAndSave()

                val duration = System.currentTimeMillis() - startTime
                if (success) {
                    L.i { "[Message][PendingMessageHelper] Direct processing completed successfully in ${duration}ms" }
                } else {
                    L.w { "[Message][PendingMessageHelper] Direct processing partial success in ${duration}ms, WebSocket will compensate" }
                }
            } catch (e: Exception) {
                L.e { "[Message][PendingMessageHelper] Direct processing failed: ${e.stackTraceToString()}" }
            } finally {
                // 确保无论成功失败都释放锁
                isProcessing.set(false)
            }
        }
    }

    companion object {
        /**
         * 防止并发执行的标志位
         */
        private val isProcessing = AtomicBoolean(false)
    }

    private suspend fun processPendingMessages(): Boolean {
        var more = true
        while (more) {
            val pendingMessageResponse = try {
                httpClient.httpService.getPendingMessage((globalServices.userManager.getUserData()?.baseAuth ?: ""))
            } catch (e: Exception) {
                L.e { "[Message][PendingMessageHelper] obtainPendingMessage failed -> ${e.stackTraceToString()}" }
                return false
            }
            more = pendingMessageResponse.more
            L.i { "[Message][PendingMessageHelper] obtainPendingMessage success size:${pendingMessageResponse.messages?.size} more:${more}" }

            val messages = pendingMessageResponse.messages ?: emptyList()

            // Sort messages by systemShowTimestamp to ensure chronological order
            val sortedMessages = messages.sortedBy { it.systemShowTimestamp }
            val failedEnvelopes = mutableListOf<SignalServiceProtos.Envelope>()

            sortedMessages.forEach { msg ->
                val envelope = buildEnvelope(msg) ?: return@forEach
                when (val processRes = envelopToMessageProcessor.process(envelope, "PendingMessageHelper")) {
                    is EnvelopeProcessResult.Success -> {
                        // G-3: dedup if this ts is still sitting in the retry queue.
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
                }
            }

            if (failedEnvelopes.isNotEmpty()) {
                L.w { "[Message][PendingMessageHelper] ${failedEnvelopes.size} messages failed transient, saving for retry" }
                failedMessageProcessor.saveTransient(failedEnvelopes)
                // Kick the retry tick so this batch doesn't have to wait for the
                // next WebSocket message before being retried (FCM/pull path
                // has no other trigger source).
                failedMessageProcessor.triggerProcess()
            }

            WCDBUpdateService.updatingRooms()
            // Delete all messages from server (successful, permanent-drop, and
            // transient-enqueued ones — same eager-ACK rationale as
            // IncomingEnvelopMessageProcessor. Transient retries are now owned
            // by the local FailedMessageProcessor.
            deletePendingMessages(messages)
        }
        return true
    }

    private suspend fun deletePendingMessages(messages: List<PendingMessage>) = coroutineScope {
        val batches = messages.chunked(10)
        batches.forEachIndexed { index, batch ->
            // 批内并发执行，提高效率
            batch.map { msg ->
                async(Dispatchers.IO) {
                    try {
                        val result = httpClient.httpService.removePendingMessage(
                            (globalServices.userManager.getUserData()?.baseAuth ?: ""),
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
            }.awaitAll()  // 等待本批全部完成

            // 批间延迟
            if (index < batches.size - 1) {
                delay(50)  // 批间小延迟
            } else {
                // 最后一批：延迟更长时间，避免重复拉取
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
                            groupId = ByteString.copyFrom(conversation.toByteArray())
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
            // buildEnvelope can fail several distinct ways: `Base64.decode`
            // throws IOException on malformed input; protobuf builder /
            // null fields can throw NPE or IllegalArgumentException. Same
            // bytes will fail the same way on retry — record a permanent
            // drop. Distinguish IOException (the Base64 path) from other
            // failures so Crashlytics aggregates them as separate issues
            // and keeps the diagnostic signal intact. ACK still happens
            // upstream (deletePendingMessages runs unconditionally).
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
