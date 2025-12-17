package com.difft.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.ui.CriticalAlertFullScreen
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import javax.inject.Inject

@AndroidEntryPoint
class CriticalAlertActivity: ComponentActivity() {

    companion object {
        @Volatile
        var isShowing = false
            private set

        @Volatile
        var mConversationId: String? = null
    }

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @ChativeHttpClientModule.Call
    @Inject
    lateinit var httpClient: ChativeHttpClient
    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }
    
    private var autoFinishJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 覆盖系统栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 隐藏状态栏
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        mConversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        if (mConversationId.isNullOrEmpty()) {
            L.e { "[CriticalAlert] conversationId is null or empty" }
            finish()
            return
        }

        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        if (title.isNullOrEmpty()) {
            L.e { "[CriticalAlert] title is null or empty" }
            finish()
            return
        }

        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        if (message.isNullOrEmpty()) {
            L.e { "[CriticalAlert] message is null or empty" }
            finish()
            return
        }

        // 所有验证通过后，设置isShowing标志
        isShowing = true

        mConversationId?.let {
            setContent {
                Content(
                    title = title,
                    message = message,
                    conversationId = it
                )
            }
        }

        registerCriticalAlertReceiver()

        // 默认延迟1分钟（60_000毫秒）后自动关闭
        scheduleAutoFinish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 需要重新处理新的Intent
        setIntent(intent)
        val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        
        if (conversationId.isNullOrEmpty() || title.isNullOrEmpty() || message.isNullOrEmpty()) {
            L.e { "[CriticalAlert] onNewIntent: Invalid parameters, finishing activity" }
            finish()
            return
        }
        
        // 更新共享的 conversationId，确保广播接收器能正确匹配
        mConversationId = conversationId
        
        // 取消之前的自动关闭任务
        autoFinishJob?.cancel()
        
        // 更新UI
        setContent {
            Content(
                title = title,
                message = message,
                conversationId = conversationId
            )
        }
        
        // 重新启动自动关闭
        scheduleAutoFinish()
    }


    private fun registerCriticalAlertReceiver() {
        val filter = IntentFilter()
        filter.addAction(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS)
        filter.addAction(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID)
        ContextCompat.registerReceiver(
            this,
            criticalAlertReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val criticalAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.`package` == ApplicationHelper.instance.packageName) {
                    when (intent.action) {
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS -> {
                            L.i { "[CriticalAlert] Received dismiss action" }
                            finishAndRemoveTask()
                        }
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID -> {
                            L.i { "[CriticalAlert] Received dismiss action by conversationId" }
                            val conversationId = it.getStringExtra(LCallConstants.CRITICAL_ALERT_PARAM_CONVERSATION)
                            if (conversationId != null && conversationId == mConversationId) {
                                finishAndRemoveTask()
                            }
                        }
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        L.i { "[CriticalAlert] onDestroy" }
        // 取消自动关闭任务
        autoFinishJob?.cancel()
        autoFinishJob = null
        isShowing = false
        mConversationId = null
        try {
            unregisterReceiver(criticalAlertReceiver)
        } catch (e: Exception) {
            // 可能已经注销，忽略异常
            L.w { "[CriticalAlert] Failed to unregister receiver: ${e.message}" }
        }
    }

    @Composable
    fun Content(
        title: String,
        message: String,
        conversationId: String
    ) {
        CriticalAlertFullScreen(
            title = title,
            message = message,
            onJoinClick = { handleJoinAction(conversationId) },
            onCloseClick = { handleCancelAction(conversationId) }
        )
    }

    private fun handleJoinAction(conversationId: String) {
        // 取消自动关闭任务
        autoFinishJob?.cancel()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 检查是否正在通话
                if (LCallActivity.isInCalling()) {
                    L.i { "[CriticalAlert] Currently in call, canceling critical alert notification" }
                    // 关闭当前 critical alert 通知（包括通知、声音以及闪光灯）
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } else {
                    // 2. 如果当前未进行通话，则调用 getCallingList 方法获取最新的会议列表
                    val response = callService.getCallingList(SecureSharedPrefsUtil.getToken())
                        .awaitFirst()

                    if (response.status != 0) {
                        L.e { "[CriticalAlert] getCallingList failed with status: ${response.status}" }
                        // 获取失败，关闭通知
                        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                    } else {
                        val calls = response.data?.calls

                        // 3. 如果会议列表为空，则关闭当前 critical alert 通知
                        if (calls.isNullOrEmpty()) {
                            L.i { "[CriticalAlert] Calling list is empty, canceling critical alert notification" }
                            messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                        } else {
                            // 4. 如果会议列表不为空，则将当前 critical alert 通知的 conversationId 与会议列表数据中的 conversation 匹配
                            val matchedCall = calls.firstOrNull { call ->
                                call.conversation == conversationId
                            }

                            if (matchedCall == null) {
                                // 5. 如果匹配失败，则关闭当前 critical alert 通知
                                L.i { "[CriticalAlert] No matching call found for conversationId: $conversationId, canceling notification" }
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                            } else {
                                matchedCall.let { data ->
                                    if (data.callName.isNullOrEmpty()) {
                                        L.e { "[CriticalAlert] Call name is null or empty for conversationId: $conversationId" }
                                        when (data.type) {
                                            CallType.ONE_ON_ONE.type -> {
                                                data.callName = LCallManager.getDisplayNameById(data.conversation)
                                            }
                                            CallType.GROUP.type -> {
                                                data.callName = LCallManager.getDisplayGroupNameById(data.conversation)
                                            }
                                        }
                                    }
                                }

                                // 6. 如果匹配成功，则加入会议
                                L.i { "[CriticalAlert] Matching call found, joining call. roomId: ${matchedCall.roomId}, conversationId: $conversationId" }

                                // 关闭通知
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)

                                withContext(Dispatchers.Main) {
                                    // 加入会议
                                    LCallManager.joinCall(this@CriticalAlertActivity, matchedCall) { status ->
                                        if (status) {
                                            L.i { "[CriticalAlert] Successfully joined call for conversationId: $conversationId" }
                                        } else {
                                            L.e { "[CriticalAlert] Failed to join call for conversationId: $conversationId" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "[CriticalAlert] Error handling notification click: ${e.message}, stackTrace: ${e.stackTraceToString()}" }
                // 发生错误时，关闭通知
                try {
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } catch (ex: Exception) {
                    L.e { "[CriticalAlert] Failed to cancel notification after error: ${ex.message}" }
                }
            } finally {
                // 完成异步操作后关闭Activity
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
        }
    }

    private fun handleCancelAction(conversationId: String) {
        // 取消自动关闭任务
        autoFinishJob?.cancel()
        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
        finish()
    }

    private fun scheduleAutoFinish(delayMillis: Long = 60_000L) {
        // 取消之前的任务
        autoFinishJob?.cancel()
        autoFinishJob = lifecycleScope.launch {
            delay(delayMillis)  // Suspend until delay completes
            if (!isFinishing && !isDestroyed) {  // Check if activity is still active
                L.i { "[CriticalAlert] Auto finishing after ${delayMillis}ms" }
                finishAndRemoveTask()  // Close activity and remove from recent tasks
            }
        }
    }
}