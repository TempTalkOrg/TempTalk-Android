package com.difft.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.CriticalAlertFullScreen
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import javax.inject.Inject
import kotlin.collections.any
import kotlin.collections.orEmpty

@AndroidEntryPoint
class CriticalAlertActivity: ComponentActivity() {

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var criticalAlertStateManager: CriticalAlertStateManager

    @Inject
    lateinit var criticalAlertManager: CriticalAlertManager

    @Inject
    lateinit var contactorCacheManager: ContactorCacheManager

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var groupRepo: GroupRepo

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    @ChativeHttpClientModule.Call
    @Inject
    lateinit var httpClient: ChativeHttpClient
    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }
    
    private var autoFinishJob: Job? = null
    
    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.i { "[CriticalAlert] onCreate" }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 覆盖系统栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 隐藏状态栏
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }


        val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        if (conversationId.isNullOrEmpty()) {
            L.e { "[CriticalAlert] conversationId is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        if (title.isNullOrEmpty()) {
            L.e { "[CriticalAlert] title is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        if (message.isNullOrEmpty()) {
            L.e { "[CriticalAlert] message is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val notificationId = intent.getIntExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, -1)
        if (notificationId == -1) {
            L.e { "[CriticalAlert] notificationId is error" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val isNotification = intent.getBooleanExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, false)

        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID)

        // 所有验证通过后，设置状态
        criticalAlertStateManager.setConversationId(conversationId)
        criticalAlertStateManager.setIsShowing(true)

        when(intent.action) {
            LCallConstants.CRITICAL_ALERT_ACTION_CLICKED -> {
                handleJoinAction(conversationId, roomId)
                showBlankTransparentUI()
            }
            else -> {
                showCriticalAlertUI(conversationId, title, message, roomId)
            }
        }

        // 注册广播接收器，用于处理用户主动关闭或超时自动关闭的事件
        registerCriticalAlertReceiver()

        // 注册返回按键和边缘手势拦截，防止用户意外退出
        registerOnBackPressedHandler()

        // 默认延迟1分钟（60_000毫秒）后自动关闭
        scheduleAutoFinish()

        // app前台启动activity 播放声音和闪光灯
        if (!isNotification) criticalAlertManager.playSoundAndFlashLight(conversationId, notificationId)
    }

    private fun showBlankTransparentUI() {
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            )
        }
    }

    private fun showCriticalAlertUI(conversationId: String, title: String, message: String, roomId: String? = null) {
        setContent {
            Content(
                title = title,
                message = message,
                conversationId = conversationId,
                roomId = roomId
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 需要重新处理新的Intent
        L.i { "[CriticalAlert] onNewIntent: Handling new intent" }
        setIntent(intent)
        val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        val notificationId = intent.getIntExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, -1)
        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID)

        if (conversationId.isNullOrEmpty() || title.isNullOrEmpty() || message.isNullOrEmpty() || notificationId == -1) {
            L.e { "[CriticalAlert] onNewIntent: Invalid parameters, finishing activity" }
            criticalAlertStateManager.reset()
            finish()
            return
        }
        
        // 更新共享的状态，确保广播接收器能正确匹配
        criticalAlertStateManager.setConversationId(conversationId)
        criticalAlertStateManager.setIsShowing(true)
        
        // 取消之前的自动关闭任务
        autoFinishJob?.cancel()
        
        // 更新UI
        when(intent.action) {
            LCallConstants.CRITICAL_ALERT_ACTION_CLICKED -> {
                handleJoinAction(conversationId, roomId)
                showBlankTransparentUI()
            }
            else -> {
                showCriticalAlertUI(conversationId, title, message, roomId)
            }
        }
        
        // 重新启动自动关闭
        scheduleAutoFinish()

        // 播放声音和闪光灯
        criticalAlertManager.playSoundAndFlashLight(conversationId, notificationId)
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
    
    /**
     * 注册返回按键和边缘手势拦截
     * 防止用户通过左滑、右滑或返回键意外退出 CriticalAlertActivity
     * 只有通过点击关闭按钮或收到广播才能关闭
     */
    private fun registerOnBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                L.i { "[CriticalAlert] Back pressed or edge swipe detected, but ignoring to prevent accidental dismissal" }
                // 不执行任何操作，防止用户意外退出
                // 用户只能通过点击关闭按钮或收到广播来关闭页面
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private val criticalAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.`package` == ApplicationHelper.instance.packageName) {
                    when (intent.action) {
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS -> {
                            L.i { "[CriticalAlert] Received dismiss action" }
                            messageNotificationUtil.cancelCriticalAlertNotification()
                            finishAndRemoveTask()
                        }
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID -> {
                            L.i { "[CriticalAlert] Received dismiss action by conversationId" }
                            val conversationId = it.getStringExtra(LCallConstants.CRITICAL_ALERT_PARAM_CONVERSATION)
                            if (conversationId != null && conversationId == criticalAlertStateManager.getConversationId()) {
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
        // 移除返回按键回调
        if (::backPressedCallback.isInitialized) {
            backPressedCallback.remove()
        }
        // 重置状态
        criticalAlertStateManager.reset()
        // 停止声音和闪光灯
        criticalAlertManager.stopSoundAndFlashLight()

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
        conversationId: String,
        roomId: String? = null
    ) {
        CriticalAlertFullScreen(
            title = title,
            message = message,
            onJoinClick = { handleJoinAction(conversationId, roomId) },
            onCloseClick = { handleCancelAction(conversationId) }
        )
    }

    private fun handleJoinAction(conversationId: String, roomId: String? = null) {
        // 取消自动关闭任务
        autoFinishJob?.cancel()

        criticalAlertStateManager.setIsJoining(true)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 检查是否正在通话
                if (onGoingCallStateManager.isInCalling()) {
                    L.i { "[CriticalAlert] Currently in call, canceling critical alert notification" }
                    // 关闭当前 critical alert 通知（包括通知、声音以及闪光灯）
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } else {
                    // 2. 如果当前未进行通话，则调用 getCallingList 方法获取最新的会议列表
                    val response = callService.getCallingList(SecureSharedPrefsUtil.getToken())
                        .await()

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
                            jumpToIndexActivity(this@CriticalAlertActivity)
                        } else {
                            // 4. 如果会议列表不为空，则将当前 critical alert 通知的 conversationId 与会议列表数据中的 conversation 匹配
                            val matchedCall = calls.firstOrNull { call ->
                                if (!roomId.isNullOrEmpty()) {
                                    call.roomId == roomId
                                } else {
                                    call.conversation == conversationId
                                }
                            }

                            if (matchedCall == null) {
                                // 5. 如果匹配失败，则关闭当前 critical alert 通知
                                L.i { "[CriticalAlert] No matching call found for conversationId: $conversationId, canceling notification" }
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                                jumpToIndexActivity(this@CriticalAlertActivity)
                            } else {
                                matchedCall.let { data ->
                                    if (data.callName.isNullOrEmpty()) {
                                        L.w { "[CriticalAlert] Call name is null or empty for conversationId: $conversationId" }
                                        L.w { "[CriticalAlert]  data: $data" }
                                        when (data.type) {
                                            CallType.ONE_ON_ONE.type -> {
                                                data.callName = contactorCacheManager.getDisplayNameById(data.conversation)
                                            }
                                            CallType.GROUP.type -> {
                                                val inGroup = checkUserIsInGroup(mySelfId, conversationId)
                                                if (inGroup) {
                                                    data.callName = contactorCacheManager.getDisplayGroupNameById(data.conversation)
                                                } else {
                                                    data.callName = "${contactorCacheManager.getDisplayNameById(conversationId)}${ResUtils.getString(
                                                        R.string.call_instant_call_title
                                                    )}"
                                                }
                                            }
                                            CallType.INSTANT.type -> {
                                                data.callName = "${contactorCacheManager.getDisplayNameById(conversationId)}${ResUtils.getString(
                                                        R.string.call_instant_call_title
                                                    )}"
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
                withContext(Dispatchers.Main) {
                    criticalAlertStateManager.setIsJoining(false)
                    // 确保在主线程中关闭Activity
                    if (!isFinishing && !isDestroyed) {
                        finish()
                    }
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

    private fun jumpToIndexActivity(context: Context) {
        try {
            // 检查 Activity 是否仍然有效
            if (isFinishing || isDestroyed) {
                L.w { "[CriticalAlert] Activity is finishing or destroyed, skipping jumpToIndexActivity" }
                return
            }
            
            val intent = Intent(context.applicationContext, activityProvider.getActivityClass(ActivityType.INDEX)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.applicationContext.startActivity(intent)
            
            // 再次检查，防止在 startActivity 后 Activity 状态改变
            if (!isFinishing && !isDestroyed) {
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            L.e { "[CriticalAlert] jumpToIndexActivity error: ${e.message}"}
        }
    }

    /**
     * 检查用户是否在群组中
     */
    private suspend fun checkUserIsInGroup(userId: String, gid: String): Boolean {
        return try {
            val resp = groupRepo.getGroupInfo(gid).await()

            val members = resp.data?.members.orEmpty()
            members.any { it.uid == userId }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler checkUserIsInGroup error: ${e.stackTraceToString()}" }
            false
        }
    }
}