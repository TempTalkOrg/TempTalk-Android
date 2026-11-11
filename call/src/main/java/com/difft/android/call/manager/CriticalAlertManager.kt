package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.NotificationRepostGuard
import com.difft.android.base.utils.appScope
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
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
    private val criticalAlertCallGate: CriticalAlertCallGate,
    private val criticalAlertRoomRegistry: CriticalAlertRoomRegistry,
    private val repostGuard: NotificationRepostGuard,
    ) {
    companion object {
        private const val CRITICAL_ALERT_RETENTION_DAYS = 7L
    }

    private val json = Json { ignoreUnknownKeys = true }

    
    @Serializable
    data class CriticalAlertInfo(
        val notificationIds: List<Int>,
        val timestamp: Long
    )


    fun hashConversationId(conversationId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    

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

    @Synchronized
    private fun saveCriticalAlertInfos(infos: Map<String, CriticalAlertInfo>) {
        try {
            val encoded = json.encodeToString(infos)
            userManager.update { criticalAlertInfos = encoded }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertManager Failed to save critical alert infos: ${e.stackTraceToString()}" }
        }
    }

    
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

    @Synchronized
    fun getCriticalAlertInfos(): Map<String, CriticalAlertInfo> {
        cleanupOldCriticalAlertCache()
        return loadCriticalAlertInfos()
    }

    @Synchronized
    fun isCriticalAlertNotificationProcessed(conversationId: String, notificationId: Int): Boolean {
        return try {
            val hashKey = hashConversationId(conversationId)
            val infos = getCriticalAlertInfos()
            val info = infos[hashKey]
            info?.notificationIds?.contains(notificationId) == true
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertManager Failed to check if critical alert notification is processed: ${e.message}" }
            false 
        }
    }

    
    fun stopSound() = criticalAlertSoundPlayer.stopSound()

    
    fun stopSoundIfMatch(notificationId: Int) = criticalAlertSoundPlayer.stopSoundIfMatch(notificationId)

    





    fun stopIfOwner(notificationId: Int) {
        criticalAlertSoundPlayer.stopIfOwner(notificationId)
    }

    
    fun recordRoomAlert(roomId: String, notificationId: Int) =
        criticalAlertRoomRegistry.record(roomId, notificationId)

    









    fun dismissForRoom(roomId: String) {
        if (roomId.isEmpty()) return
        try {
            val ids = criticalAlertRoomRegistry.end(roomId)
            val pageId = criticalAlertStateManager.getPageNotificationId()
            if (pageId != null && pageId in ids) {
                context.sendBroadcast(Intent(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_NOTIFICATION_ID).apply {
                    `package` = context.packageName
                    putExtra(LCallConstants.CRITICAL_ALERT_PARAM_NOTIFICATION_ID, pageId)
                })
            }
            ids.forEach { id ->
                repostGuard.cancel(id)
                stopIfOwner(id)
            }
            L.i { "[CriticalAlert] dismissForRoom roomId=$roomId alerts=${ids.size} pageClosed=${pageId != null && pageId in ids}" }
        } catch (e: Exception) {
            L.e { "[CriticalAlert] dismissForRoom failed roomId=$roomId error=${e.stackTraceToString()}" }
        }
    }

    






    fun publishPageAndCheckSilence(notificationId: Int, conversationId: String, roomId: String?): Boolean {
        criticalAlertStateManager.setPageNotificationId(notificationId)
        return criticalAlertCallGate.shouldSilence(conversationId, roomId)
    }

    




    fun releasePage(notificationId: Int, conversationId: String?, stopOwnedAlert: Boolean) {
        if (stopOwnedAlert) stopIfOwner(notificationId)
        criticalAlertStateManager.clearPage(notificationId, conversationId)
    }

    






    fun shouldDismissWhenIndexStarts(): Boolean {
        val snapshot = criticalAlertStateManager.getStateSnapshot()
        return (snapshot.isPlayingSound || snapshot.isShowing) && !snapshot.isJoining && !snapshot.isPageVisible &&
            
            !(snapshot.pageClosingSilentlyId != null && snapshot.pageClosingSilentlyId == snapshot.pageNotificationId)
    }

    fun isCriticalAlertShowing(conversationId: String?): Boolean {
        if(conversationId == null) return false
        
        val snapshot = criticalAlertStateManager.getStateSnapshot()
        return (snapshot.conversationId == conversationId) && (snapshot.isPlayingSound || snapshot.isShowing)
    }

    fun startCriticalAlertActivity(conversationId: String, title: String, content: String, notificationId: Int, roomId: String? = null) {
        
        appScope.launch(Dispatchers.Main) {
            
            var retryCount = 0
            val maxRetries = 10 
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

    fun playSoundAndFlashLight(conversationId: String, notificationId: Int, roomId: String? = null) {
        appScope.launch(Dispatchers.IO) {
            
            
            criticalAlertSoundPlayer.playSound(conversationId, notificationId, roomId) {
                if (FlashLightBlinker.hasCameraPermission(context)) {
                    
                    
                    FlashLightBlinker.startBlinking(context, durationMs = 30000) {
                        !criticalAlertCallGate.shouldSilence(conversationId, roomId) &&
                            criticalAlertSoundPlayer.ownsPlayToken(notificationId)
                    }
                }
            }
        }
    }

}
