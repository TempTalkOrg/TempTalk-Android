package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val wcdb: WCDB,
) {
    companion object {
        private const val CLEANUP_DAYS_THRESHOLD = 10L
    }

    private val processEvents = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var hasCleanedUp = false

    /**
     * This function will auto call when this class's instance is created by Hilt.
     */
    @Inject
    fun initWhenInject() {
        processEvents.sampleAfterFirst(3000).onEach {
            // 一次性获取所有待处理消息
            val pendingMessages = wcdb.pendingMessageNew.allObjects

            L.i { "[PendingMessageProcessor] pendingMessages:${pendingMessages.size}" }

            if (pendingMessages.isEmpty()) {
                return@onEach
            }

            // 获取所有原始消息时间戳
            val originalMessageTimestamps = pendingMessages.map { it.originalMessageTimeStamp }.distinct()

            // 批量查询已存在的原始消息
            val existingMessages = wcdb.message.getAllObjects(
                DBMessageModel.timeStamp.`in`(originalMessageTimestamps)
            ).map { it.timeStamp }.toSet()

            // 只处理已存在原始消息的待处理消息
            val messagesToProcess = pendingMessages.filter { it.originalMessageTimeStamp in existingMessages }
            val processedTimestamps = mutableListOf<Long>()

            // 处理所有待处理消息，单条异常不影响其他消息处理
            messagesToProcess.forEach { pendingMessage ->
                try {
                    envelopToMessageProcessor.process(Envelope.parseFrom(pendingMessage.messageEnvelopBytes), "pending-timestamp")
                    processedTimestamps.add(pendingMessage.originalMessageTimeStamp)
                } catch (e: Exception) {
                    L.e { "[PendingMessageProcessor] Failed to process pending message ${pendingMessage.originalMessageTimeStamp}: ${e.stackTraceToString()}" }
                    // Continue processing other messages
                }
            }

            // 批量删除已处理的消息
            if (processedTimestamps.isNotEmpty()) {
                wcdb.pendingMessageNew.deleteObjects(
                    DBPendingMessageModelNew.originalMessageTimeStamp.`in`(processedTimestamps)
                )
            }

            // 删除超过指定天数的待处理消息（脏数据清理）- 每个启动周期只执行一次
            if (!hasCleanedUp) {
                val cleanupThreshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CLEANUP_DAYS_THRESHOLD)
                val oldMessages = pendingMessages.filter { it.originalMessageTimeStamp < cleanupThreshold }

                if (oldMessages.isNotEmpty()) {
                    L.i { "[PendingMessageProcessor] Deleting ${oldMessages.size} old pending messages" }
                    wcdb.pendingMessageNew.deleteObjects(
                        DBPendingMessageModelNew.originalMessageTimeStamp.`in`(oldMessages.map { it.originalMessageTimeStamp })
                    )
                }
                hasCleanedUp = true
            }
        }.launchIn(appScope)
    }

    fun triggerProcess() {
        processEvents.tryEmit(Unit)
    }
}