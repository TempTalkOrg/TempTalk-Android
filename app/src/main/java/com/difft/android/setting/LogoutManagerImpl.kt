package com.difft.android.setting

import android.content.Intent
import android.os.Process
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import difft.android.messageserialization.MessageStore
import com.difft.android.network.config.WsTokenManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import org.difft.app.database.WCDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.cache.OfficialAccountCache
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.ForegroundServiceUtil
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class LogoutManagerImpl @Inject constructor(
    private val userManager: UserManager,
    private val storageBoundUserManager: StorageBoundUserManager,
    private var messageStore: MessageStore,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val wsTokenManager: WsTokenManager,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val webSocketManager: WebSocketManager,
    private val coordinator: DomainSpeedTestCoordinator,
    private val wcdb: WCDB,
    private val proxyConfigProvider: ProxyConfigProvider,
) : LogoutManager {
    override fun doLogout() {
        performLogout(clearAllData = true)
    }


    override fun doLogoutWithoutRemoveData() {
        performLogout(clearAllData = false)
    }

    private fun performLogout(clearAllData: Boolean) {
        appScope.launch {
            if (clearAllData) {
                clearData()
            } else {
                // 只清除登录凭证
                userManager.update(true) {
                    this.baseAuth = null

                    this.passcode = null
                    this.passcodeAttempts = 0
                    this.pattern = null
                    this.patternAttempts = 0
                }
                // Reset unread badge counter. `clearAuthOnly()` only zeroes auth credentials
                // in `secure_user`; it deliberately preserves `app_state` (UX/UI fields), so
                // we explicitly clear the unread counter here.
                userManager.update { unreadMsgNum = 0 }
                ContactRemarkCache.clear()
                OfficialAccountCache.clear()
                // Mirror the full-clear path: invalidate the proxy provider's in-memory
                // @Volatile cache eagerly so it doesn't keep serving the previous user's
                // TURN secret until the next refreshFromUserDataIfChanged() read. The
                // on-disk wipe of proxyShareLink/proxyEnabled happens inside
                // clearStoragesForAuthOnly() -> StorageBoundUserManager.clearAuthOnly();
                // this call is the in-memory belt-and-braces.
                runCatching { proxyConfigProvider.clear() }
                    .onFailure { L.w { "[Proxy] clear during passive logout failed: ${it.message}" } }
                clearStoragesForAuthOnly()
                // Issue #754: failed_message retry queue is per-account. On
                // passive logout the WCDB itself survives, so clear the table
                // explicitly to prevent retries of the previous user's envelopes
                // under the next account's session.
                runCatching { wcdb.failedMessage.deleteObjects() }
                    .onFailure { L.w { "[Logout] clear failedMessage failed: ${it.stackTraceToString()}" } }
            }
            appIconBadgeManager.updateAppIconBadgeNum(0)

            messageNotificationUtil.cancelAllNotifications()
            wsTokenManager.clearToken()
            coordinator.resetSession()
            stopMessageService()
            webSocketManager.stop()

            withContext(Dispatchers.Main) {
                restartApp()
            }
        }
    }

    /**
     * Clear all data related to the user.
     */
    private fun clearData() {
        userManager.setUserData(UserData(), true)

        // Plain app_state and secure_user DataStores are cleared inside
        // [clearStoragesForFullClear] -> [StorageBoundUserManager.clearAll].
        //
        // The legacy `wcdb_secure_prefs.xml` is intentionally NOT cleared here — it is
        // kept as a cold recovery backup (same policy as other legacy SP files post
        // issue #725). On the next install/login, WCDBKeyManager generates a fresh
        // key for the new DB regardless of what is in the legacy SP.

        // Best-effort delete of the WCDB cipher-key blob (file may already be gone on partial clears).
        runCatching { File(application.filesDir, "wcdb_key.bin").delete() }
        runCatching { File(application.filesDir, "wcdb_key.bin.tmp").delete() }

        messageStore.deleteDatabase()

        ContactRemarkCache.clear()
        OfficialAccountCache.clear()

        // Unconditional clear on logout: the share link embeds the coturn
        // `static-auth-secret` (when present) — that's a user-bound secret and
        // must not persist across account boundaries. Wrapped in runCatching so
        // a clear failure cannot block the rest of the logout sequence.
        runCatching { proxyConfigProvider.clear() }
            .onFailure { L.w { "[Proxy] clear during logout failed: ${it.message}" } }

        FileUtil.clearAllFilesExceptLogs()

        clearStoragesForFullClear()
    }

    // 3s bounded block: logout is user-initiated and followed by restartApp(); stays below the ANR threshold.

    /** Bridges [StorageBoundUserManager.clearAuthOnly] under a 3 s timeout before [restartApp]. */
    @Suppress("BanRunBlockingOutsideTests")
    private fun clearStoragesForAuthOnly() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                storageBoundUserManager.clearAuthOnly()
            } ?: L.w { "[Logout][Island1A] clearAuthOnly timed out — proceeding to restartApp" }
            L.i { "[Logout][Island1A] auth-only clear complete" }
        }
    }

    /** Bridges [StorageBoundUserManager.clearAll] under a 3 s timeout before [restartApp]. */
    @Suppress("BanRunBlockingOutsideTests")
    private fun clearStoragesForFullClear() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                storageBoundUserManager.clearAll()
            } ?: L.w { "[Logout][Island1B] clearAll timed out — proceeding to restartApp" }
            L.i { "[Logout][Island1B] full clear complete" }
        }
    }

    private fun stopMessageService() {
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)
    }

    /**
     * Relaunches the app via the package's launch intent, then kills the process.
     * `FLAG_ACTIVITY_CLEAR_TASK` requires `FLAG_ACTIVITY_NEW_TASK` on API ≥ 24 when
     * called from a non-Activity context — without it, `startActivity` silently fails.
     */
    private fun restartApp(): Nothing {
        application.packageManager.getLaunchIntentForPackage(application.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                application.startActivity(this)
            } catch (e: Exception) {
                L.e { "[Logout] restartApp startActivity failed: ${e.stackTraceToString()}" }
            }
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
