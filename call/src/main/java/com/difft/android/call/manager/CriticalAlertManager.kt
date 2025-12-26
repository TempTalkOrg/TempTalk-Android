package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.call.R
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.util.FlashLightBlinker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CriticalAlertManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val gson: Gson,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val activityProvider: ActivityProvider,
    private val inComingCallStateManager: InComingCallStateManager,
    ) {
    // 用于防止并发竞争的互斥锁
    private val soundMutex = Mutex()
    
    // 当前播放的 Ringtone 对象
    private var currentRingtone: Ringtone? = null
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

    /**
     * 播放 Critical Alert 声音
     * @param conversationId 会话ID
     * @param notificationId 通知ID
     */
    fun playSound(conversationId: String, notificationId: Int) {
        val token = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            soundMutex.withLock {
                // 更新当前 token，清除旧播放
                criticalAlertStateManager.setCurrentPlayToken(token)
                stopSoundInternal()
            }

            try {
                val ringtoneUri =
                    Uri.parse("android.resource://${context.packageName}/${R.raw.critical_alert}")
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }

                soundMutex.withLock {
                    // 若此时已有更新的播放任务，则放弃当前任务
                    if (token != criticalAlertStateManager.getCurrentPlayToken()) {
                        L.i { "CriticalAlertManager Ignored old play call for id=$notificationId (token=$token)" }
                        // 清理未使用的ringtone对象，防止资源泄漏
                        try {
                            ringtone?.stop()
                        } catch (e: Exception) {
                            L.e { "CriticalAlertManager Failed to stop ringtone during cleanup: ${e.message}" }
                        }
                        return@withLock
                    }

                    currentRingtone = ringtone
                    criticalAlertStateManager.setCurrentNotificationId(notificationId)
                    criticalAlertStateManager.setConversationId(conversationId)
                    L.i { "CriticalAlertManager Playing ringtone for notification $notificationId" }
                    ringtone?.play()
                    criticalAlertStateManager.setIsPlayingSound(true)
                }

            } catch (e: Exception) {
                L.e { "CriticalAlertManager Play failed: ${e.message}" }
            }
        }
    }

    /**
     * 停止播放 Critical Alert 声音
     */
    fun stopSound() {
        appScope.launch(Dispatchers.IO) {
            soundMutex.withLock {
                stopSoundInternal()
            }
        }
    }

    /**
     * 如果通知ID匹配，则停止播放声音
     * @param notificationId 通知ID
     */
    fun stopSoundIfMatch(notificationId: Int) {
        appScope.launch(Dispatchers.IO) {
            soundMutex.withLock {
                if (notificationId == criticalAlertStateManager.getCurrentNotificationId()) {
                    stopSoundInternal()
                    L.i { "CriticalAlertManager Stopped ringtone for $notificationId" }
                }
            }
        }
    }

    /**
     * 内部方法：停止播放声音
     */
    private fun stopSoundInternal() {
        try {
            currentRingtone?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (e: Exception) {
            L.e { "CriticalAlertManager Stop failed: ${e.message}" }
        } finally {
            currentRingtone = null
            criticalAlertStateManager.resetSoundState()
        }
    }

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

    fun startCriticalAlertActivity(conversationId: String, title: String, content: String) {
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
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun stopSoundAndFlashLight() {
        stopSound()
        FlashLightBlinker.stopBlinking(application)
    }

}
