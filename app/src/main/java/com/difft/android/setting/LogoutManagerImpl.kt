package com.difft.android.setting

import android.content.Intent
import android.os.Process
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import difft.android.messageserialization.MessageStore
import com.difft.android.network.config.WsTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBSecureSharedPrefsUtil
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.util.AppIconBadgeManager
import org.thoughtcrime.securesms.util.ForegroundServiceUtil
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.websocket.WebSocketManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class LogoutManagerImpl @Inject constructor(
    private val userManager: UserManager,
    private var messageStore: MessageStore,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val wsTokenManager: WsTokenManager,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val webSocketManager: WebSocketManager
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
                SharedPrefsUtil.putInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM, 0)
            }
            appIconBadgeManager.updateAppIconBadgeNum(0)

            messageNotificationUtil.cancelAllNotifications()
            wsTokenManager.clearToken()
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

        SharedPrefsUtil.clear()
        SecureSharedPrefsUtil.clear()

        WCDBSecureSharedPrefsUtil(application).clear()
        messageStore.deleteDatabase()

        FileUtil.clearAllFilesExceptLogs()
    }

    private fun stopMessageService() {
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)
    }

    private fun restartApp(): Nothing {
        application.packageManager.getLaunchIntentForPackage(application.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            application.startActivity(this)
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}