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
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.PackageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [NotificationChannelManager.reconcileCriticalAlertBypass]. */
enum class CriticalAlertBypass {
    /** No Do Not Disturb access (or no channel support): nothing to reconcile. */
    NOT_APPLICABLE,

    /** The critical-alert category bypasses Do Not Disturb. */
    ENABLED,

    /** Access is granted but the category still does not bypass; only the user can switch it on. */
    NEEDS_USER_ACTION,
}

/**
 * Creates, renames and repairs the app's own notification channels.
 *
 * - [ensureChannels] runs at every start: creates what is missing and refreshes localized display
 *   names in place (`createNotificationChannel` on an existing id only updates name and
 *   description, never the user's importance/sound choices).
 * - [migrateIfNeeded] runs once per [CHANNEL_VERSION] and only drops the obsolete legacy
 *   critical-alert channel; no channel in use is ever deleted.
 * - [reconcileCriticalAlertBypass] re-applies bypass-Do-Not-Disturb, which AOSP only honors on a
 *   later `createNotificationChannel` call and only while the user has not touched the category.
 */
@Singleton
class NotificationChannelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:AppStateDataStore private val appState: DataStore<Preferences>,
) {

    companion object {
        /** Bump to run [migrateIfNeeded] once more after the next release. */
        const val CHANNEL_VERSION = 1

        /** Remove the pre-V2 critical-alert channel id the app no longer posts to. */
        const val DELETE_LEGACY_CRITICAL_ALERT = true

        private val MESSAGE_GROUP_IDS = setOf(NotificationChannelIds.MESSAGE, NotificationChannelIds.MESSAGE_SUMMARY)

        /** Channels whose spec is silent; older builds may have created them audible. */
        private val SILENT_CHANNELS = listOf(NotificationChannelIds.ONGOING_CALL, NotificationChannelIds.BACKGROUND)

        private val CORE_CHANNELS = listOf(
            NotificationChannelIds.MESSAGE_SUMMARY,
            NotificationChannelIds.MESSAGE,
            NotificationChannelIds.CALL,
            NotificationChannelIds.ONGOING_CALL,
            NotificationChannelIds.BACKGROUND,
            NotificationChannelIds.CRITICAL_ALERT,
        )
    }

    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Cheap and synchronous: safe on the startup path, and channels must exist before any post. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureGroup()
        CORE_CHANNELS.forEach { id ->
            runCatching { ensureChannel(id) }
                .onFailure { L.e { "[NotificationChannel] ensure failed id=$id error=${it.stackTraceToString()}" } }
        }
        runCatching { renameExisting(NotificationChannelIds.ACCOUNT) }
            .onFailure { L.w { "[NotificationChannel] rename failed id=${NotificationChannelIds.ACCOUNT}" } }
    }

    /**
     * One-time upkeep per [CHANNEL_VERSION]; never deletes a channel the app posts to (a delete
     * cancels its notifications and re-creation restores the old settings anyway). It lowers the
     * importance of channels meant to be silent that older builds created audible, and removes the
     * obsolete critical-alert channel id when [DELETE_LEGACY_CRITICAL_ALERT] is on. Off the main
     * thread: reads app_state. The version is stamped last, so an interrupted run retries.
     */
    suspend fun migrateIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val applied = appState.data.first()[AppStateKeys.NOTIFICATION_CHANNEL_VERSION]
            ?: AppStateDefaults.NOTIFICATION_CHANNEL_VERSION
        if (applied >= CHANNEL_VERSION) return
        val lowered = lowerSilentChannels()
        var deletedLegacy = false
        if (DELETE_LEGACY_CRITICAL_ALERT && nm.getNotificationChannel(NotificationChannelIds.LEGACY_CRITICAL_ALERT) != null) {
            deleteSafely(NotificationChannelIds.LEGACY_CRITICAL_ALERT)
            deletedLegacy = true
        }
        appState.edit { it[AppStateKeys.NOTIFICATION_CHANNEL_VERSION] = CHANNEL_VERSION }
        L.i { "[NotificationChannel] migrated version=$CHANNEL_VERSION deletedLegacyCritical=$deletedLegacy loweredSilent=$lowered previous=$applied" }
    }

    /**
     * Brings the critical-alert category's bypass-Do-Not-Disturb in line with the granted policy
     * access, including for users who were granted access before this reconcile existed. The
     * re-create is attempted at most once per [appVersion]; the result is read back because the
     * system silently keeps the user's own setting.
     */
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

    /**
     * Older builds created ONGOING_CALL audible (DEFAULT). AOSP lets an app lower a channel's
     * importance only while the user has not touched it, so the replica carries
     * min(existing, spec): it never raises anything, and a user's own choice is kept by the system.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun lowerSilentChannels(): Int {
        var lowered = 0
        SILENT_CHANNELS.forEach { id ->
            runCatching {
                val existing = nm.getNotificationChannel(id) ?: return@runCatching
                if (existing.importance <= NotificationManager.IMPORTANCE_LOW) return@runCatching
                nm.createNotificationChannel(replica(existing, NotificationManager.IMPORTANCE_LOW))
                lowered++
            }.onFailure { L.w { "[NotificationChannel] lower failed id=$id error=${it.javaClass.simpleName}" } }
        }
        return lowered
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureGroup() {
        runCatching {
            val name = context.getString(requireNotNull(NotificationChannelIds.nameRes(NotificationChannelIds.MESSAGE_GROUP)))
            // notificationChannelGroups is API 26; getNotificationChannelGroup(String) is API 28.
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

    /**
     * Re-submits an existing channel under its localized name. The replica carries the current
     * importance, group and bypass flag, so the update cannot lower or reset anything.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun renameExisting(id: String) {
        val existing = nm.getNotificationChannel(id) ?: return
        val name = displayName(id) ?: return
        if (existing.name?.toString() == name) return
        nm.createNotificationChannel(replica(existing, existing.importance, name))
        L.i { "[NotificationChannel] renamed id=$id" }
    }

    /** A copy of [existing] to re-submit: same group and bypass flag, the given [importance] and name. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun replica(existing: NotificationChannel, importance: Int, name: String? = null): NotificationChannel {
        val id = existing.id
        return NotificationChannel(id, name ?: displayName(id) ?: existing.name, minOf(importance, existing.importance)).apply {
            // A legacy channel without a group gets one: AOSP assigns it only when the existing group is null.
            group = existing.group ?: NotificationChannelIds.MESSAGE_GROUP.takeIf { id in MESSAGE_GROUP_IDS }
            setBypassDnd(existing.canBypassDnd())
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun deleteSafely(id: String) {
        try {
            nm.deleteNotificationChannel(id)
        } catch (e: Exception) {
            L.e { "[NotificationChannel] delete failed id=$id error=${e.stackTraceToString()}" }
        }
    }

    private fun displayName(id: String): String? = NotificationChannelIds.nameRes(id)?.let { context.getString(it) }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildChannel(id: String): NotificationChannel {
        val name = displayName(id) ?: id
        return when (id) {
            NotificationChannelIds.MESSAGE_SUMMARY ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    group = NotificationChannelIds.MESSAGE_GROUP
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
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
            NotificationChannelIds.ONGOING_CALL, NotificationChannelIds.BACKGROUND ->
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            else -> error("No channel spec for id=$id")
        }
    }
}
