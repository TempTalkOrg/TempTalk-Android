package com.difft.android.setting

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.HeldSendJobs
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseScope
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
    
    
    private val translateModelStore: dagger.Lazy<TranslateModelStore>,
    private val translateQueue: dagger.Lazy<TranslateQueue>,
    private val migrationMessagePause: MigrationMessagePause,
    private val heldSendJobs: HeldSendJobs,
) : LogoutManager {
    private val logoutLatch = SingleShotLatch()

    
    
    @Volatile
    private var fullClearRequested = false

    override fun doLogout() {
        performLogout(clearAllData = true)
    }


    override fun doLogoutWithoutRemoveData() {
        performLogout(clearAllData = false)
    }

    





    override fun doMigrationHandoffSignOut() {
        performLogout(clearAllData = false, silent = true)
    }

    private fun performLogout(clearAllData: Boolean, silent: Boolean = false) {
        
        
        
        if (!logoutLatch.tryAcquire()) {
            if (clearAllData) {
                fullClearRequested = true
                L.w { "[Logout] full clear requested while passive logout in flight, escalating" }
            } else {
                L.w { "[Logout] performLogout already in progress, ignoring duplicate call clearAllData=$clearAllData" }
            }
            return
        }
        
        
        forwardAttachmentMigration.cancel()
        appScope.launch {
            
            
            
            
            
            
            if (!clearAllData) {
                val endingLogin = userManager.getUserData()?.baseAuth?.takeIf { it.isNotBlank() }?.let(MigrationPauseScope::credentialHash)
                val paused = migrationMessagePause.state is MessagingPauseState.Paused
                heldSendJobs.dropAll("logout", keepLogin = if (paused) null else endingLogin)
            }
            if (!migrationMessagePause.clearForLogout()) {
                L.w { "[Logout] migration pause clear failed" }
            }
            if (clearAllData) {
                clearData()
            } else {
                userManager.update(true) {
                    this.baseAuth = null

                    this.passcode = null
                    this.passcodeAttempts = 0
                    this.passcodeAutoMisses = 0
                    this.pattern = null
                    this.patternAttempts = 0
                    this.microToken = null
                }
                
                
                
                userManager.update { unreadMsgNum = 0 }
                ContactRemarkCache.clear()
                OfficialAccountCache.clear()
                
                
                
                
                
                
                runCatching { proxyConfigProvider.clear() }
                    .onFailure { L.w { "[Proxy] clear during passive logout failed: ${it.message}" } }
                clearStoragesForAuthOnly()
                
                
                
                
                runCatching { wcdb.failedMessage.deleteObjects() }
                    .onFailure { L.w { "[Logout] clear failedMessage failed: ${it.stackTraceToString()}" } }
            }
            appIconBadgeManager.updateAppIconBadgeNum(0)

            messageNotificationUtil.cancelAllNotifications()

            
            
            
            val hasHost = appVisibility.hasResumedActivity()
            if (!hasHost && !silent) {
                
                
                L.i { "[Logout] no resumed activity, posting logged-out notification before exit" }
                loggedOutNotifier.post()
            }

            coordinator.resetSession()
            stopMessageService()
            webSocketManager.stop()

            
            
            if (!clearAllData && fullClearRequested) {
                L.i { "[Logout] escalating in-flight passive logout to a full clear" }
                clearData()
            }

            withContext(Dispatchers.Main) {
                appExiter.exit(relaunch = hasHost)
            }
        }
    }

    


    private fun clearData() {
        
        
        
        
        
        
        
        
        runCatching {
            translateQueue.get().cancelAll()
            translateModelStore.get().clearOnLogout()
        }.onFailure { L.w { "[Translate] logout cleanup failed: ${it.stackTraceToString()}" } }

        userManager.setUserData(UserData(), true)

        
        
        
        
        
        
        

        
        runCatching { File(application.filesDir, "wcdb_key.bin").delete() }
        runCatching { File(application.filesDir, "wcdb_key.bin.tmp").delete() }

        messageStore.deleteDatabase()

        ContactRemarkCache.clear()
        OfficialAccountCache.clear()

        
        
        
        
        runCatching { proxyConfigProvider.clear() }
            .onFailure { L.w { "[Proxy] clear during logout failed: ${it.message}" } }

        FileUtil.clearAllFilesExceptLogs()

        clearStoragesForFullClear()
    }

    

    
    @Suppress("BanRunBlockingOutsideTests")
    private fun clearStoragesForAuthOnly() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                storageBoundUserManager.clearAuthOnly()
            } ?: L.w { "[Logout][Island1A] clearAuthOnly timed out — proceeding to exit" }
            L.i { "[Logout][Island1A] auth-only clear complete" }
        }
    }

    
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





internal class SingleShotLatch {
    private val acquired = AtomicBoolean(false)

    fun tryAcquire(): Boolean = acquired.compareAndSet(false, true)
}
