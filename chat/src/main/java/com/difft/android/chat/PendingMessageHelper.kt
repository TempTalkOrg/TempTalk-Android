package com.difft.android.chat

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.WCDBUpdateService
import com.difft.android.base.utils.globalServices
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.PendingMessage
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.thoughtcrime.securesms.messages.EnvelopToMessageProcessor
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.envelope
import org.whispersystems.signalservice.internal.push.msgExtra
import com.difft.android.websocket.util.Base64
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PendingMessageHelper @Inject constructor(
    @ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val dbMessageStore: DBMessageStore
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
     * 调度待处理消息的WorkManager任务
     * 使用唯一工作确保同时只有一个任务在执行
     */
    fun schedulePendingMessageWork(context: Context) {
        val pendingMessageWork = OneTimeWorkRequestBuilder<PendingMessageWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(false) // 允许在低电量时执行
                    .setRequiresStorageNotLow(false)  // 允许在存储空间不足时执行
                    .build()
            )
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        // 使用enqueueUniqueWork，确保同时只有一个任务在执行
        // 使用KEEP策略，如果已有任务在执行，则跳过新任务，避免取消正在执行的任务
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "pending_message_work",           // 唯一名称
                ExistingWorkPolicy.KEEP,          // 如果已有任务，保持现有任务，跳过新任务
                pendingMessageWork
            )

        L.i { "[Message][PendingMessageHelper] Scheduled unique pending message work with KEEP policy" }
    }

    private suspend fun processPendingMessages(): Boolean {
        var more = true
        while (more) {
            val pendingMessageResponse = try {
                httpClient.httpService.getPendingMessage(SecureSharedPrefsUtil.getBasicAuth())
            } catch (e: Exception) {
                L.e { "[Message][PendingMessageHelper] obtainPendingMessage failed -> ${e.stackTraceToString()}" }
                return false
            }
            more = pendingMessageResponse.more
            L.i { "[Message][PendingMessageHelper] obtainPendingMessage success size:${pendingMessageResponse.messages?.size} more:${more}" }

            val messages = pendingMessageResponse.messages ?: emptyList()
            val results = messages.mapNotNull { msg ->
                buildEnvelope(msg)?.let { envelope ->
                    envelopToMessageProcessor.process(envelope, "PendingMessageHelper")
                }
            }

            WCDBUpdateService.updatingRooms()

            // 批量保存消息
            val validMessages = results.map { it.message }
            if (validMessages.isNotEmpty()) {
                L.i { "[Message][PendingMessageHelper] saving ${validMessages.size} messages to db" }
                try {
                    dbMessageStore.putWhenNonExist(*validMessages.toTypedArray(), useTransaction = false)
                } catch (e: Exception) {
                    L.e { "[Message][PendingMessageHelper] Failed to save messages to DB: ${e.stackTraceToString()}" }
                    return false
                }
            }

            deletePendingMessages(messages)
        }
        return true
    }

    @SuppressLint("CheckResult")
    private suspend fun deletePendingMessages(messages: List<PendingMessage>) {
        messages.chunked(5).forEach { batch -> // 批量删除，减少网络压力
            batch.forEach { msg ->
                httpClient.httpService.removePendingMessage(
                    SecureSharedPrefsUtil.getBasicAuth(),
                    msg.source.toString(),
                    msg.timestamp.toString()
                ).subscribe({
                    if (it.isSuccess()) {
                        L.i { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} success" }
                    } else {
                        L.e { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} failed -> ${it.reason}" }
                    }
                }, {
                    L.e { "[Message][PendingMessageHelper] remove pending message ${msg.timestamp} failed -> ${it.stackTraceToString()}" }
                })
            }
            delay(100) // 批次间小延迟，避免过于频繁的请求
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
                message.message?.let {
                    Base64.decode(it)?.let { legacyData ->
                        legacyMessage = ByteString.copyFrom(legacyData)
                    }
                }
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
            L.e { "[Message][PendingMessageHelper] Failed to build envelope from pending message: ${e.stackTraceToString()}" }
            return null
        }
    }
}