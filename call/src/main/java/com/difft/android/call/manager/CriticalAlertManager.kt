package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SharedPrefsUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CriticalAlertManager @Inject constructor(
    private val gson: Gson,
) {
    companion object {
        // Critical Alert 持久化存储相关常量
        private const val SP_KEY_CRITICAL_ALERT_INFOS = "SP_KEY_CRITICAL_ALERT_INFOS"
        private const val CRITICAL_ALERT_RETENTION_DAYS = 7L // 7天清理一次
    }

    /**
     * Critical Alert 通知信息数据类
     * 存储每个会话的通知ID列表和最后更新时间戳
     */
    data class CriticalAlertInfo(
        val notificationIds: List<Int>,
        val timestamp: Long // 最后更新时间，用于判断是否过期
    )


    /**
     * 将 conversationId 转换为哈希值（用于安全存储）
     */
    fun hashConversationId(conversationId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 从 SharedPrefsUtil 读取 Critical Alert 通知信息
     * @return Map<hashCode, CriticalAlertInfo>
     */
    @Synchronized
    private fun loadCriticalAlertInfos(): MutableMap<String, CriticalAlertInfo> {
        return try {
            val json = SharedPrefsUtil.getString(SP_KEY_CRITICAL_ALERT_INFOS)
            if (json.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                val type = object : TypeToken<Map<String, CriticalAlertInfo>>() {}.type
                gson.fromJson<Map<String, CriticalAlertInfo>>(json, type)?.toMutableMap() ?: mutableMapOf()
            }
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] Failed to load critical alert infos: ${e.message}" }
            mutableMapOf()
        }
    }

    /**
     * 保存 Critical Alert 通知信息到 SharedPrefsUtil
     */
    @Synchronized
    private fun saveCriticalAlertInfos(infos: Map<String, CriticalAlertInfo>) {
        try {
            val json = gson.toJson(infos)
            SharedPrefsUtil.putString(SP_KEY_CRITICAL_ALERT_INFOS, json)
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] Failed to save critical alert infos: ${e.message}" }
        }
    }

    /**
     * 清理过期的 Critical Alert 通知信息（超过7天的数据）
     */
    @Synchronized
    fun cleanupOldCriticalAlertCache() {
        try {
            val now = System.currentTimeMillis()
            val retentionTime = now - CRITICAL_ALERT_RETENTION_DAYS * 24 * 60 * 60 * 1000L

            val infos = loadCriticalAlertInfos()
            val expiredKeys = mutableListOf<String>()

            infos.forEach { (hashKey, info) ->
                if (info.timestamp < retentionTime) {
                    expiredKeys.add(hashKey)
                }
            }

            if (expiredKeys.isNotEmpty()) {
                expiredKeys.forEach { infos.remove(it) }
                saveCriticalAlertInfos(infos)
                L.i { "[MessageNotificationUtil] Cleanup completed: removed ${expiredKeys.size} expired critical alert entries" }
            } else {
                L.d { "[MessageNotificationUtil] No expired critical alert cache to clean" }
            }
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] Cleanup critical alert cache failed: ${e.stackTraceToString()}" }
        }
    }

    /**
     * 添加 Critical Alert 通知信息（如果不存在则添加）
     * @return true表示成功添加，false表示已存在
     */
    @Synchronized
    fun addCriticalAlertNotificationIfNotExists(conversationId: String, notificationId: Int): Boolean {
        val hashKey = hashConversationId(conversationId)
        val infos = loadCriticalAlertInfos()
        val info = infos[hashKey] ?: CriticalAlertInfo(notificationIds = emptyList(), timestamp = System.currentTimeMillis())
        val notificationIds = info.notificationIds.toMutableList()

        if (!notificationIds.contains(notificationId)) {
            notificationIds.add(notificationId)
            infos[hashKey] = CriticalAlertInfo(
                notificationIds = notificationIds,
                timestamp = System.currentTimeMillis()
            )
            saveCriticalAlertInfos(infos)
            L.d { "[MessageNotificationUtil] Added notificationId=$notificationId to cache for conversationId=$conversationId (hashKey=$hashKey), total notifications=${notificationIds.size}"}
            return true
        } else {
            L.w { "[MessageNotificationUtil] NotificationId=$notificationId already exists in cache for conversationId=$conversationId (hashKey=$hashKey), skip adding"}
            return false
        }
    }

    /**
     * 添加 Critical Alert 通知信息（不检查是否存在，直接添加）
     * 用于在系统已显示但缓存中没有的情况
     */
    @Synchronized
    fun addCriticalAlertNotification(conversationId: String, notificationId: Int) {
        val hashKey = hashConversationId(conversationId)
        val infos = loadCriticalAlertInfos()
        val info = infos[hashKey] ?: CriticalAlertInfo(notificationIds = emptyList(), timestamp = System.currentTimeMillis())
        val notificationIds = info.notificationIds.toMutableList()

        if (!notificationIds.contains(notificationId)) {
            notificationIds.add(notificationId)
            infos[hashKey] = CriticalAlertInfo(
                notificationIds = notificationIds,
                timestamp = System.currentTimeMillis()
            )
            saveCriticalAlertInfos(infos)
            L.d { "[MessageNotificationUtil] Added notificationId=$notificationId to cache for conversationId=$conversationId (hashKey=$hashKey), total notifications=${notificationIds.size}"}
        }
    }

    /**
     * 获取 Critical Alert 通知信息
     */
    @Synchronized
    fun getCriticalAlertInfos(): Map<String, CriticalAlertInfo> {
        // 读取时自动清理过期数据
        cleanupOldCriticalAlertCache()
        return loadCriticalAlertInfos()
    }

    /**
     * 检查 Critical Alert 通知是否已经在本地缓存中处理过
     * @param conversationId 会话ID
     * @param notificationId 通知ID
     * @return true表示已经处理过，false表示未处理过
     */
    @Synchronized
    fun isCriticalAlertNotificationProcessed(conversationId: String, notificationId: Int): Boolean {
        return try {
            val hashKey = hashConversationId(conversationId)
            val infos = getCriticalAlertInfos()
            val info = infos[hashKey]
            info?.notificationIds?.contains(notificationId) == true
        } catch (e: Exception) {
            L.e { "[MessageNotificationUtil] Failed to check if critical alert notification is processed: ${e.message}" }
            false // 出错时返回false，允许继续处理
        }
    }

}
