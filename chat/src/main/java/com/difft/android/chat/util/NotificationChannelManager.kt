package com.difft.android.chat.util

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.PackageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton


enum class CriticalAlertBypass {
    
    NOT_APPLICABLE,

    
    ENABLED,

    
    NEEDS_USER_ACTION,
}


enum class ChannelDeletion {
    DELETED,
    ABSENT,

    
    DEFERRED,

    
    FAILED,
}














@Singleton
class NotificationChannelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:AppStateDataStore private val appState: DataStore<Preferences>,
) {

    companion object {
        
        const val CHANNEL_VERSION = 2

        private val MESSAGE_GROUP_IDS = setOf(NotificationChannelIds.MESSAGE)

        
        private val SHARED_SPEC_IDS = setOf(NotificationChannelIds.OTHER_ALERTS, NotificationChannelIds.RUNNING_IN_BACKGROUND)

        
        private val UNKNOWN_EXEMPT_PREFIXES = listOf(NotificationChannelIds.MESSAGE, "android.")
        private val UNKNOWN_EXEMPT_IDS = setOf("fcm_fallback_notification_channel", "livekit_screen_capture", "miscellaneous")
    }

    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureGroup()
        NotificationChannelIds.KEEP.forEach { id ->
            runCatching { ensureChannel(id) }
                .onFailure { L.e { "[NotificationChannel] ensure failed id=$id error=${it.stackTraceToString()}" } }
        }
    }

    
    fun ensureMessageChannel(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            if (nm.getNotificationChannel(NotificationChannelIds.MESSAGE) == null) {
                ensureGroup()
                nm.createNotificationChannel(buildChannel(NotificationChannelIds.MESSAGE))
            }
            nm.getNotificationChannel(NotificationChannelIds.MESSAGE) != null
        } catch (e: Exception) {
            L.w { "[NotificationChannel] ensure failed id=${NotificationChannelIds.MESSAGE} error=${e.javaClass.simpleName}" }
            false
        }
    }

    





    suspend fun migrateIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = appState.data.first()
        val applied = prefs[AppStateKeys.NOTIFICATION_CHANNEL_VERSION] ?: AppStateDefaults.NOTIFICATION_CHANNEL_VERSION
        val existing = try {
            nm.notificationChannels.orEmpty()
        } catch (e: Exception) {
            L.w { "[NotificationChannel] list failed error=${e.javaClass.simpleName}" }
            return
        }
        val existingIds = existing.map { it.id }.toSet()
        logUnknownOnce(existingIds, prefs[AppStateKeys.NOTIFICATION_CHANNEL_UNKNOWN_LOGGED] ?: AppStateDefaults.NOTIFICATION_CHANNEL_UNKNOWN_LOGGED)
        val present = NotificationChannelIds.OBSOLETE.filter { it in existingIds }
        val bgBlocked = existing.any {
            it.id == NotificationChannelIds.OBSOLETE_BACKGROUND && it.importance == NotificationManager.IMPORTANCE_NONE
        }
        val results = present.associateWith { deleteSafely(it) }
        val deferred = results.filterValues { it == ChannelDeletion.DEFERRED }.keys
        val failed = results.filterValues { it == ChannelDeletion.FAILED }.keys
        val deleted = results.count { it.value == ChannelDeletion.DELETED }
        val stamp = deferred.isEmpty() && applied < CHANNEL_VERSION
        if (stamp) appState.edit { it[AppStateKeys.NOTIFICATION_CHANNEL_VERSION] = CHANNEL_VERSION }
        if (present.isNotEmpty() || stamp) {
            L.i {
                "[NotificationChannel] migrated version=$CHANNEL_VERSION deleted=$deleted deferred=$deferred" +
                    " failed=$failed bgBlocked=$bgBlocked previous=$applied"
            }
        }
    }

    





    suspend fun reconcileCriticalAlertBypass(appVersion: String = PackageUtil.getAppVersionName().orEmpty()): CriticalAlertBypass {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CriticalAlertBypass.NOT_APPLICABLE
        if (!nm.isNotificationPolicyAccessGranted) return CriticalAlertBypass.NOT_APPLICABLE
        val channel = nm.getNotificationChannel(NotificationChannelIds.CRITICAL_ALERT)
            ?: return CriticalAlertBypass.NOT_APPLICABLE
        if (channel.canBypassDnd()) return CriticalAlertBypass.ENABLED

        val attempted = appState.data.first()[AppStateKeys.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION]
            ?: AppStateDefaults.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION
        if (attempted != appVersion) {
            nm.createNotificationChannel(buildChannel(NotificationChannelIds.CRITICAL_ALERT))
            appState.edit { it[AppStateKeys.CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION] = appVersion }
        }
        val applied = nm.getNotificationChannel(NotificationChannelIds.CRITICAL_ALERT)?.canBypassDnd() == true
        if (attempted != appVersion) {
            L.i { "[NotificationChannel] criticalAlert bypass reconcile attempted applied=$applied" }
        }
        return if (applied) CriticalAlertBypass.ENABLED else CriticalAlertBypass.NEEDS_USER_ACTION
    }

    




    @RequiresApi(Build.VERSION_CODES.O)
    private fun deleteSafely(id: String): ChannelDeletion {
        if (id == NotificationChannelIds.OBSOLETE_MESSAGE_SUMMARY && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val inUse = try {
                nm.activeNotifications.any { it.notification.channelId == id }
            } catch (e: Exception) {
                L.w { "[NotificationChannel] active lookup failed id=$id error=${e.javaClass.simpleName}" }
                true
            }
            if (inUse) return ChannelDeletion.DEFERRED
        }
        return try {
            nm.deleteNotificationChannel(id)
            ChannelDeletion.DELETED
        } catch (e: SecurityException) {
            
            L.w { "[NotificationChannel] delete deferred id=$id error=${e.javaClass.simpleName}" }
            ChannelDeletion.DEFERRED
        } catch (e: Exception) {
            L.e { "[NotificationChannel] delete failed id=$id error=${e.stackTraceToString()}" }
            ChannelDeletion.FAILED
        }
    }

    private suspend fun logUnknownOnce(existingIds: Set<String>, alreadyLogged: Boolean) {
        if (alreadyLogged) return
        val unknown = existingIds.filterNot { id ->
            id in NotificationChannelIds.KEEP || id in NotificationChannelIds.OBSOLETE || id in UNKNOWN_EXEMPT_IDS ||
                UNKNOWN_EXEMPT_PREFIXES.any { id.startsWith(it) }
        }
        if (unknown.isEmpty()) return
        L.i { "[NotificationChannel] unknown ids=$unknown" }
        appState.edit { it[AppStateKeys.NOTIFICATION_CHANNEL_UNKNOWN_LOGGED] = true }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureGroup() {
        runCatching {
            val name = context.getString(requireNotNull(NotificationChannelIds.nameRes(NotificationChannelIds.MESSAGE_GROUP)))
            
            val existing = nm.notificationChannelGroups.firstOrNull { it.id == NotificationChannelIds.MESSAGE_GROUP }
            if (existing == null || existing.name?.toString() != name) {
                nm.createNotificationChannelGroup(NotificationChannelGroup(NotificationChannelIds.MESSAGE_GROUP, name))
            }
        }.onFailure { L.e { "[NotificationChannel] group ensure failed error=${it.stackTraceToString()}" } }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel(id: String) {
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(buildChannel(id))
        } else {
            renameExisting(id)
        }
    }

    



    @RequiresApi(Build.VERSION_CODES.O)
    private fun renameExisting(id: String) {
        val existing = nm.getNotificationChannel(id) ?: return
        val name = displayName(id) ?: return
        if (existing.name?.toString() == name) return
        nm.createNotificationChannel(replica(existing, name))
        L.i { "[NotificationChannel] renamed id=$id" }
    }

    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun replica(existing: NotificationChannel, name: String): NotificationChannel {
        val id = existing.id
        return NotificationChannel(id, name, existing.importance).apply {
            
            group = existing.group ?: NotificationChannelIds.MESSAGE_GROUP.takeIf { id in MESSAGE_GROUP_IDS }
            setBypassDnd(existing.canBypassDnd())
        }
    }

    private fun displayName(id: String): String? = NotificationChannelIds.nameRes(id)?.let { context.getString(it) }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildChannel(id: String): NotificationChannel {
        if (id in SHARED_SPEC_IDS) return NotificationChannelSpecs.build(context, id)
        val name = displayName(id) ?: id
        return when (id) {
            NotificationChannelIds.MESSAGE ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    group = NotificationChannelIds.MESSAGE_GROUP
                    setShowBadge(true)
                    enableVibration(true)
                }
            NotificationChannelIds.CALL ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 100)
                }
            NotificationChannelIds.CRITICAL_ALERT ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    enableVibration(true)
                    setBypassDnd(true)
                    vibrationPattern = longArrayOf(0, 100)
                }
            else -> error("No channel spec for id=$id")
        }
    }
}
