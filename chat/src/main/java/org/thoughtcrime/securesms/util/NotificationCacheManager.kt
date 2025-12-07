package org.thoughtcrime.securesms.util

import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.models.DBNotificationCacheModel
import org.difft.app.database.models.NotificationCacheModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知缓存管理器
 * 负责通知消息的持久化存储和读取（使用WCDB数据库）
 */
@Singleton
class NotificationCacheManager @Inject constructor() {

    companion object {
        // 缓存保留时间：1天（通知的实效性不需要太长，1天足够应对app重启等场景）
        private const val CACHE_RETENTION_DAYS = 1L
    }

    /**
     * 通知消息数据结构(用于外部调用)
     * 存储消息发送时刻的快照数据，包括发送人的显示名称
     */
    data class NotificationMessageData(
        val content: String,
        val timestamp: Long,
        val personKey: String,  // 发送人ID
        val personName: String  // 发送人显示名称（快照）
    )

    /**
     * 获取某个会话的消息列表（按时间排序）
     */
    fun getMessages(conversationId: String): MutableList<NotificationMessageData> {
        return try {
            val models = wcdb.notificationCache.getAllObjects(
                DBNotificationCacheModel.conversationId.eq(conversationId)
            )
            // 按时间戳排序
            models.sortedBy { it.timestamp }.map { model ->
                NotificationMessageData(
                    content = model.content,
                    timestamp = model.timestamp,
                    personKey = model.personKey,
                    personName = model.personName
                )
            }.toMutableList()
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Get messages for $conversationId failed: ${e.message}" }
            mutableListOf()
        }
    }


    /**
     * 添加一条消息到会话
     * 使用 insertOrReplace，如果相同 conversationId + timestamp 已存在则替换
     */
    fun addMessage(conversationId: String, message: NotificationMessageData) {
        try {
            val model = NotificationCacheModel().apply {
                this.conversationId = conversationId
                this.timestamp = message.timestamp
                this.content = message.content
                this.personKey = message.personKey
                this.personName = message.personName
                this.createdAt = System.currentTimeMillis()
            }
            wcdb.notificationCache.insertOrReplaceObject(model)
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Add message for $conversationId failed: ${e.message}" }
        }
    }

    /**
     * 根据时间戳删除消息(用于撤回)
     * 基于联合主键 (conversationId + timestamp) 精确删除
     */
    fun removeMessageByTimestamp(conversationId: String, timestamp: Long): Int {
        return try {
            // 删除指定的消息（联合主键精确匹配）
            wcdb.notificationCache.deleteObjects(
                DBNotificationCacheModel.conversationId.eq(conversationId)
                    .and(DBNotificationCacheModel.timestamp.eq(timestamp))
            )
            // 由于是联合主键，最多只会删除1条
            L.i { "[NotificationCacheManager] Removed message for $conversationId timestamp:$timestamp" }
            1
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Remove message by timestamp failed: ${e.message}" }
            0
        }
    }

    /**
     * 删除某个会话的所有缓存
     */
    fun removeConversation(conversationId: String) {
        try {
            wcdb.notificationCache.deleteObjects(
                DBNotificationCacheModel.conversationId.eq(conversationId)
            )
            L.d { "[NotificationCacheManager] Removed conversation cache for $conversationId" }
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Remove conversation failed: ${e.message}" }
        }
    }


    /**
     * 清空所有缓存
     */
    fun clearAll() {
        try {
            wcdb.notificationCache.deleteObjects()
            L.i { "[NotificationCacheManager] All caches cleared" }
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Clear all failed: ${e.message}" }
        }
    }


    /**
     * 清理过期的通知缓存
     * 删除超过指定天数的消息记录
     */
    fun cleanupOldCache() {
        try {
            val now = System.currentTimeMillis()
            val retentionTime = now - CACHE_RETENTION_DAYS * 24 * 60 * 60 * 1000L

            // 查询过期数据的数量
            val expiredCount = wcdb.notificationCache.getValue(
                DBNotificationCacheModel.conversationId.count(),
                DBNotificationCacheModel.createdAt.lt(retentionTime)
            )?.int ?: 0

            if (expiredCount > 0) {
                // 删除过期数据（一条SQL删除所有过期记录）
                wcdb.notificationCache.deleteObjects(
                    DBNotificationCacheModel.createdAt.lt(retentionTime)
                )
                L.i { "[NotificationCacheManager] Cleanup completed: removed $expiredCount expired messages" }
            } else {
                L.d { "[NotificationCacheManager] No expired cache to clean" }
            }
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Cleanup failed: ${e.stackTraceToString()}" }
        }
    }
}
