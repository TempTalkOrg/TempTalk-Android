package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.util.FlashLightBlinker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CriticalAlertManager @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val activityProvider: ActivityProvider,
    private val inComingCallStateManager: InComingCallStateManager,
    private val userManager: UserManager,
    private val criticalAlertSoundPlayer: CriticalAlertSoundPlayer,
    ) {
    companion object {
        private const val CRITICAL_ALERT_RETENTION_DAYS = 7L // 7天清理一次
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Critical Alert 通知信息数据类
     * 存储每个会话的通知ID列表和最后更新时间戳
     */
    @Serializable
    data class CriticalAlertInfo(
        val notificationIds: List<Int>,
        val timestamp: Long
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
     * 从 UserManager 内存快照读取 Critical Alert 通知信息
     * @return Map<hashCode, CriticalAlertInfo>
     */
    @Synchronized
    private fun loadCriticalAlertInfos(): MutableMap<String, CriticalAlertInfo> {
        return try {
            val raw = userManager.getUserData()?.criticalAlertInfos
            if (raw.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                json.decodeFromString<Map<String, CriticalAlertInfo>>(raw).toMutableMap()
            }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertManager Failed to load critical alert infos: ${e.stackTraceToString()}" }
            mutableMapOf()
        }
    }

    /**
     * 保存 Critical Alert 通知信息到 UserManager (与 [loadCriticalAlertInfos] 配对)。
     */
    @Synchronized
    private fun saveCriticalAlertInfos(infos: Map<String, CriticalAlertInfo>) {
        try {
            val encoded = json.encodeToString(infos)
            userManager.update { criticalAlertInfos = encoded }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertManager Failed to save critical alert infos: ${e.stackTraceToString()}" }
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
                L.i { "[Call] CriticalAlertManager Cleanup completed: removed ${expiredKeys.size} expired critical alert entries" }
            } else {
                L.d { "[Call] CriticalAlertManager No expired critical alert cache to clean" }
            }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertManager Cleanup critical alert cache failed: ${e.stackTraceToString()}" }
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
            L.d { "[Call] CriticalAlertManager Added notificationId=$notificationId to cache for conversationId=$conversationId (hashKey=$hashKey), total notifications=${notificationIds.size}"}
            return true
        } else {
            L.w { "[Call] CriticalAlertManager NotificationId=$notificationId already exists in cache for conversationId=$conversationId (hashKey=$hashKey), skip adding"}
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
            L.d { "[Call] CriticalAlertManager Added notificationId=$notificationId to cache for conversationId=$conversationId (hashKey=$hashKey), total notifications=${notificationIds.size}"}
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
            L.e { "[Call] CriticalAlertManager Failed to check if critical alert notification is processed: ${e.message}" }
            false // 出错时返回false，允许继续处理
        }
    }

    /**
     * 停止播放 Critical Alert 声音（同步非阻塞，可安全在主线程调用）。委托给 [CriticalAlertSoundPlayer]。
     */
    fun stopSound() = criticalAlertSoundPlayer.stopSound()

    /**
     * 如果通知ID匹配，则停止播放声音（同步调用）。委托给 [CriticalAlertSoundPlayer]。
     */
    fun stopSoundIfMatch(notificationId: Int) = criticalAlertSoundPlayer.stopSoundIfMatch(notificationId)

    fun isCriticalAlertShowing(conversationId: String?): Boolean {
        if(conversationId == null) return false
        // 使用原子性快照确保线程安全，避免并发状态更新导致的不一致
        val snapshot = criticalAlertStateManager.getStateSnapshot()
        return (snapshot.conversationId == conversationId) && (snapshot.isPlayingSound || snapshot.isShowing)
    }

    fun isCriticalAlertRunning(): Boolean {
        val snapshot = criticalAlertStateManager.getStateSnapshot()
        return snapshot.isPlayingSound || snapshot.isShowing
    }

    fun startCriticalAlertActivity(conversationId: String, title: String, content: String, notificationId: Int, roomId: String? = null) {
        // 如果 LIncomingCallActivity 正在显示，等待它关闭后再启动 CriticalAlertActivity
        // 这样可以避免 CriticalAlertActivity 的背景显示为黑色（实际上是 LIncomingCallActivity 的窗口还在显示）
        appScope.launch(Dispatchers.Main) {
            // 等待 LIncomingCallActivity 关闭（最多等待500ms）
            var retryCount = 0
            val maxRetries = 10 // 10次 * 50ms = 500ms
            while (inComingCallStateManager.isActivityShowing() && retryCount < maxRetries) {
                delay(50)
                retryCount++
            }
            
            if (inComingCallStateManager.isActivityShowing()) {
                L.w { "[CriticalAlert] LIncomingCallActivity is still showing after ${maxRetries * 50}ms, starting CriticalAlertActivity anyway" }
            } else {
                L.i { "[CriticalAlert] LIncomingCallActivity closed, starting CriticalAlertActivity after ${retryCount * 50}ms" }
            }
            
            val intent = Intent(context, activityProvider.getActivityClass(ActivityType.CRITICAL_ALERT)).apply {
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION, conversationId)
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE, title)
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE, content)
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, notificationId)
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, false)
                putExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID, roomId)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun stopSoundAndFlashLight() {
        stopSound()
        FlashLightBlinker.stopBlinking()
    }

    fun playSoundAndFlashLight(conversationId: String, notificationId: Int) {
        appScope.launch(Dispatchers.IO) {
            // 播放critical alert声音（循环铃声 + 同步循环震动 + 30s 兜底）
            criticalAlertSoundPlayer.playSound(conversationId, notificationId)
            // 启动闪光灯
            if (FlashLightBlinker.hasCameraPermission(context)) {
                FlashLightBlinker.startBlinking(context, durationMs = 30000)
            }
        }
    }

}
