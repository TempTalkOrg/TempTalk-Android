package com.difft.android.chat.message

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.setting.archive.MessageArchiveManager
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地消息创建工具类
 *
 * 提供创建和保存本地消息的通用方法
 * 使用 Dagger 注入，内部处理 MessageStore 和消息过期时间获取
 */
@Singleton
class LocalMessageCreator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageStore: MessageStore,
    private val messageArchiveManager: MessageArchiveManager
) {

    companion object {
        private const val TAG = "LocalMessageCreator"

        /**
         * 生成消息 ID
         * 格式：timestamp + uid(去除+号) + deviceId
         *
         * @param timestamp 消息时间戳
         * @param uid 用户 ID
         * @param deviceId 设备 ID，默认为 DEFAULT_DEVICE_ID
         */
        fun generateMessageId(timestamp: Long, uid: String, deviceId: Int = DEFAULT_DEVICE_ID): String {
            return StringBuilder().apply {
                append(timestamp)
                append(uid.replace("+", ""))
                append(deviceId)
            }.toString()
        }
    }

    /**
     * 创建并保存 Critical Alert 本地消息
     *
     * 此方法会自动获取消息过期时间并在 IO 线程执行数据库操作
     *
     * @param systemShowTimestamp 服务器系统时间戳
     * @param timestamp 消息时间戳（时间戳）
     * @param fromWho 消息发送者
     * @param forWhat 消息所属会话
     * @param sourceDevice 消息所属设备类型
     */
    suspend fun createCriticalAlertMessage(
        systemShowTimestamp: Long,
        timestamp: Long,
        fromWho: For,
        forWhat: For,
        sourceDevice: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val expiresInSeconds = messageArchiveManager.getMessageArchiveTime(forWhat, false).await().toInt()
            val messageId = generateMessageId(timestamp, fromWho.id, sourceDevice)
            val text = context.getString(R.string.chat_message_critical_alert)
            val textMessage = TextMessage(
                id = messageId,
                fromWho = fromWho,
                forWhat = forWhat,
                text = text,
                systemShowTimestamp = systemShowTimestamp,
                timeStamp = timestamp,
                receivedTimeStamp = System.currentTimeMillis(),
                sendType = 1,
                expiresInSeconds = expiresInSeconds,
                notifySequenceId = 0,
                sequenceId = 0,
                atPersons = null,
                quote = null,
                forwardContext = null,
                recall = null,
                card = null,
                mode = 0,
                criticalAlertType = CRITICAL_ALERT_TYPE_ALERT
            ).apply {
                if (forWhat is For.Group) {
                    val receiverIds = wcdb.groupMemberContactor
                        .getAllObjects(DBGroupMemberContactorModel.gid.eq(forWhat.id))
                        .asSequence()
                        .map { it.id }
                        .filter { it != globalServices.myId }
                        .toMutableSet()
                    if (receiverIds.isNotEmpty()) {
                        this.receiverIds = globalServices.gson.toJson(receiverIds)
                    }
                }
            }

            messageStore.putWhenNonExist(textMessage)
            L.i { "[$TAG] createCriticalAlertMessage success, messageId=$messageId, expiresInSeconds=$expiresInSeconds" }
        }.onFailure { error ->
            L.e { "[$TAG] createCriticalAlertMessage error: ${error.message}" }
        }
    }
}