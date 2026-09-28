package com.difft.android.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import difft.android.messageserialization.MessageStore
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
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.translate.TranslateQueue
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.ForegroundServiceUtil
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogoutManagerImpl @Inject constructor(
    private val userManager: UserManager,
    private val storageBoundUserManager: StorageBoundUserManager,
    private var messageStore: MessageStore,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val webSocketManager: WebSocketManager,
    private val coordinator: DomainSpeedTestCoordinator,
    private val wcdb: WCDB,
    private val proxyConfigProvider: ProxyConfigProvider,
    private val forwardAttachmentMigration: ForwardAttachmentMigration,
    private val loggedOutNotifier: LoggedOutNotifier,
    private val appVisibility: AppVisibility,
    private val appExiter: AppExiter,
    // Lazy: both are @Singleton graph leaves that the vast majority of sessions never touch, and a
    // logout must not be the thing that constructs them (TranslateQueue starts a worker in init).
    private val translateModelStore: dagger.Lazy<TranslateModelStore>,
    private val translateQueue: dagger.Lazy<TranslateQueue>,
) : LogoutManager {
    private val logoutLatch = SingleShotLatch()

    // Set when a user-initiated full wipe arrives while a passive logout already holds the latch.
    // Read by the running sequence, written by the losing caller's thread.
    @Volatile
    private var fullClearRequested = false

    override fun doLogout() {
        performLogout(clearAllData = true)
    }


    override fun doLogoutWithoutRemoveData() {
        performLogout(clearAllData = false)
    }

    /**
     * Same field set and teardown as [doLogoutWithoutRemoveData], minus the "you were signed out"
     * notification: the user deliberately moved this account to the new package. No server call —
     * the account stays registered, and chat data, attachments and app_state (which carry the
     * handoff markers) are kept.
     */
    override fun doMigrationHandoffSignOut() {
        performLogout(clearAllData = false, silent = true)
    }

    private fun performLogout(clearAllData: Boolean, silent: Boolean = false) {
        // Passive logout is now app-scoped, so a kick confirm and a user-initiated "Log Out" can be
        // in flight at once. A duplicate passive logout is a no-op, but a full wipe must never be
        // swallowed by an auth-only sequence — it escalates the one already running instead.
        if (!logoutLatch.tryAcquire()) {
            if (clearAllData) {
                fullClearRequested = true
                L.w { "[Logout] full clear requested while passive logout in flight, escalating" }
            } else {
                L.w { "[Logout] performLogout already in progress, ignoring duplicate call clearAllData=$clearAllData" }
            }
            return
        }
        // Stop the attachment migration before the database and the files it is scanning are wiped,
        // so it does not spend the moments before the exit logging IO errors about deleted rows.
        forwardAttachmentMigration.cancel()
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
                    this.microToken = null
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

            // Resumed-Activity check, NOT AppForegroundObserver: ProcessLifecycleOwner debounces
            // ON_STOP by ~700 ms, and a logout confirmed in that window would skip the notice and
            // then die silently. See AppVisibility.
            val hasHost = appVisibility.hasResumedActivity()
            if (!hasHost && !silent) {
                // Post strictly after cancelAllNotifications() (which cancels everything) and
                // before the teardown below, so the notify() binder call lands before the kill.
                L.i { "[Logout] no resumed activity, posting logged-out notification before exit" }
                loggedOutNotifier.post()
            }

            coordinator.resetSession()
            stopMessageService()
            webSocketManager.stop()

            // clearData() is an idempotent superset of the auth-only clear, so escalating here is
            // safe even though the auth-only branch has already run.
            if (!clearAllData && fullClearRequested) {
                L.i { "[Logout] escalating in-flight passive logout to a full clear" }
                clearData()
            }

            withContext(Dispatchers.Main) {
                appExiter.exit(relaunch = hasHost)
            }
        }
    }

    /**
     * Clear all data related to the user.
     */
    private fun clearData() {
        // Before the database goes away: drop every queued translation and release the model-download
        // bookkeeping. A queued job is dropped outright; the one job already inside ML Kit cannot be
        // interrupted, so it runs to the end, but cancelAll() bumps the queue generation and a
        // completion from an older generation is never broadcast — nothing therefore writes a
        // translate row into a store that is being wiped.
        // Downloaded ML Kit models are deliberately kept — they are a device resource with no account
        // affinity. Passive logout does NOT come through here: the conversation translate setting and
        // the failure records survive a kick, exactly like saveToPhotos.
        runCatching {
            translateQueue.get().cancelAll()
            translateModelStore.get().clearOnLogout()
        }.onFailure { L.w { "[Translate] logout cleanup failed: ${it.stackTraceToString()}" } }

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

    // 3s bounded block: logout is user-initiated and followed by process exit; stays below the ANR threshold.

    /** Bridges [StorageBoundUserManager.clearAuthOnly] under a 3 s timeout before the exit. */
    @Suppress("BanRunBlockingOutsideTests")
    private fun clearStoragesForAuthOnly() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                storageBoundUserManager.clearAuthOnly()
            } ?: L.w { "[Logout][Island1A] clearAuthOnly timed out — proceeding to exit" }
            L.i { "[Logout][Island1A] auth-only clear complete" }
        }
    }

    /** Bridges [StorageBoundUserManager.clearAll] under a 3 s timeout before the exit. */
    @Suppress("BanRunBlockingOutsideTests")
    private fun clearStoragesForFullClear() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                storageBoundUserManager.clearAll()
            } ?: L.w { "[Logout][Island1B] clearAll timed out — proceeding to exit" }
            L.i { "[Logout][Island1B] full clear complete" }
        }
    }

    private fun stopMessageService() {
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)
    }
}

/**
 * One-shot gate. Deliberately never released: every path through the logout sequence ends in
 * process death, so a second acquisition would always be a duplicate rather than a retry.
 */
internal class SingleShotLatch {
    private val acquired = AtomicBoolean(false)

    fun tryAcquire(): Boolean = acquired.compareAndSet(false, true)
}
