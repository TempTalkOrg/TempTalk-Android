package com.difft.android.chat.util

import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.models.DBNotificationCacheModel
import org.difft.app.database.models.NotificationCacheModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class NotificationCacheManager @Inject constructor() {

    companion object {
        
        private const val CACHE_RETENTION_DAYS = 1L
    }

    


    data class NotificationMessageData(
        val content: String,
        val timestamp: Long,
        val personKey: String,
        val personName: String  
    )

    fun getMessages(conversationId: String): MutableList<NotificationMessageData> {
        return try {
            val models = wcdb.notificationCache.getAllObjects(
                DBNotificationCacheModel.conversationId.eq(conversationId)
            )
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

    


    fun removeMessageByTimestamp(conversationId: String, timestamp: Long): Int {
        return try {
            wcdb.notificationCache.deleteObjects(
                DBNotificationCacheModel.conversationId.eq(conversationId)
                    .and(DBNotificationCacheModel.timestamp.eq(timestamp))
            )
            L.i { "[NotificationCacheManager] Removed message for $conversationId timestamp:$timestamp" }
            1
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Remove message by timestamp failed: ${e.message}" }
            0
        }
    }

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


    fun clearAll() {
        try {
            wcdb.notificationCache.deleteObjects()
            L.i { "[NotificationCacheManager] All caches cleared" }
        } catch (e: Exception) {
            L.e { "[NotificationCacheManager] Clear all failed: ${e.message}" }
        }
    }


    fun cleanupOldCache() {
        try {
            val now = System.currentTimeMillis()
            val retentionTime = now - CACHE_RETENTION_DAYS * 24 * 60 * 60 * 1000L

            val expiredCount = wcdb.notificationCache.getValue(
                DBNotificationCacheModel.conversationId.count(),
                DBNotificationCacheModel.createdAt.lt(retentionTime)
            )?.int ?: 0

            if (expiredCount > 0) {
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
