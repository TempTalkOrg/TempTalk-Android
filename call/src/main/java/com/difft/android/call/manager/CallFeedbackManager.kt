package com.difft.android.call.manager

import com.difft.android.base.utils.globalServices

import android.app.Activity
import androidx.compose.ui.platform.ComposeView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.ui.feedback.CallRatingFeedbackView
import com.difft.android.call.util.CallComposeUiUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话反馈管理器
 * 负责管理通话反馈相关的逻辑
 */
@Singleton
class CallFeedbackManager @Inject constructor(
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
    @param:AppStateDataStore private val appStateStore: DataStore<Preferences>,
    private val userManager: com.difft.android.base.user.UserManager,
) {
    private val callService by lazy {
        callHttpClient.get().getService(LCallHttpService::class.java)
    }

    // 存储反馈信息
    private var callFeedbackInfo: FeedbackCallInfo? = null

    // ==================== 反馈触发逻辑相关常量 ====================
    private companion object {
        private const val RESET_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
    }

    /** Bounded synchronous read; pre-warmed DataStore typically returns in sub-ms, 1 s cap guards cold start. */
    @Suppress("BanRunBlockingOutsideTests")
    private fun <T> readBlocking(read: suspend (Preferences) -> T?, default: T): T =
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(1_000) { read(appStateStore.data.first()) } ?: default
        }

    @Suppress("BanRunBlockingOutsideTests")
    private fun writeBlocking(edit: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(1_000) { appStateStore.edit(edit) }
            }.onFailure {
                L.w { "[Call] CallFeedbackManager write failed: ${it.message}" }
            }
        }
    }
    
    /**
     * 提交通话反馈到服务器
     * 使用 Kotlin Coroutines 实现异步提交，不阻塞调用线程
     * 
     * @param params 反馈请求参数
     */
    fun submitCallFeedback(params: CallFeedbackRequestBody) {
        appScope.launch {
            submitCallFeedbackInternal(params)
        }
    }
    
    /**
     * 内部方法：提交通话反馈到服务器
     * 使用 suspend 函数实现，支持协程调用
     * 
     * @param params 反馈请求参数
     */
    private suspend fun submitCallFeedbackInternal(params: CallFeedbackRequestBody) {
        val token = (globalServices.userManager.getUserData()?.microToken ?: "")
        if (token.isNullOrEmpty()) {
            L.e { "[Call] CallFeedbackManager submitCallFeedback failed: missing authentication token" }
            return
        }
        
        try {
            val response = withContext(Dispatchers.IO) {
                callService.callFeedback(token, params)
            }
            
            if (response.status == 0) {
                L.i { "[Call] CallFeedbackManager submitCallFeedback, request success" }
            } else {
                L.e { "[Call] CallFeedbackManager submitCallFeedback, request fail: ${response.reason}" }
            }
        } catch (error: Exception) {
            L.e { "[Call] CallFeedbackManager submitCallFeedback, request fail, error: ${error.message}" }
        }
    }
    
    /**
     * 显示通话反馈视图
     * 在Activity上添加Compose视图用于显示反馈界面
     * 
     * @param activity 要显示反馈视图的Activity
     * @param callInfo 反馈信息
     */
    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        val composeView = ComposeView(activity)
        composeView.setContent {
            // This overlay never owns the host Activity's window — the host is whatever
            // Activity onActivityResumed happened to land on, not this call site's own root.
            DifftTheme(applyWindowBackground = false) {
                CallRatingFeedbackView(
                    callInfo = callInfo,
                    onDisplay = {
                        clearCallFeedbackInfo()
                    },
                    onDismiss = {
                        CallComposeUiUtil.removeComposeViewFromActivity(activity, composeView)
                    },
                    onSubmit = { data ->
                        submitCallFeedback(data)
                    }
                )
            }
        }
        try {
            CallComposeUiUtil.addComposeViewToActivity(activity, composeView)
        } catch (e: Exception) {
            L.e { "[Call] CallFeedbackManager Feedback addComposeViewToActivity error: ${e.message}" }
        }
    }
    
    /**
     * 设置反馈信息
     * 
     * @param info 反馈信息，null 表示清空
     */
    fun setCallFeedbackInfo(info: FeedbackCallInfo?) {
        callFeedbackInfo = info
    }
    
    /**
     * 清空反馈信息
     */
    fun clearCallFeedbackInfo() {
        callFeedbackInfo = null
    }
    
    /**
     * 获取并清空反馈信息
     * 原子操作：获取当前反馈信息后立即清空
     * 
     * @return 当前的反馈信息，如果不存在则返回 null
     */
    fun getAndClearCallFeedbackInfo(): FeedbackCallInfo? {
        val info = callFeedbackInfo
        callFeedbackInfo = null
        return info
    }
    
    /**
     * 获取当前反馈信息（不清空）
     * 
     * @return 当前的反馈信息，如果不存在则返回 null
     */
    fun getCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackInfo
    }
    
    // ==================== 反馈触发逻辑 ====================
    
    /**
     * Check whether the 24-hour period has been exceeded, and if so, reset the data (generate a new threshold and clear the count)
     */
    @Synchronized
    private fun ensure24HourReset() {
        val snapshot = userManager.getUserData()
        val lastReset = snapshot?.callLastFeedbackResetTime ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastReset >= RESET_INTERVAL_MS || lastReset == 0L) {
            val newThreshold = (1..5).random()
            // commit = true so hasTriggered=false is durably persisted BEFORE
            // CALL_COUNT is reset to 0. If we crash between the two writes with
            // the userManager write still in flight, the next session would see
            // hasTriggered=true on disk + CALL_COUNT=0, suppressing feedback for
            // the entire next 24-hour window. Same crash-consistency reasoning
            // as shouldTriggerFeedback() below.
            userManager.update(commit = true) {
                callLastFeedbackResetTime = now
                callFeedbackRandomThreshold = newThreshold
                callFeedbackHasTriggered = false
            }
            // CALL_COUNT remains in appStateStore (no UserData mapping).
            writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = 0 }
        }
    }

    /**
     * Called at the end of each call, automatically increments the call count and checks if feedback should be triggered
     *
     * @param isForce 是否强制触发反馈
     * @return 是否应该触发反馈
     */
    @Synchronized
    fun shouldTriggerFeedback(isForce: Boolean): Boolean {
        ensure24HourReset()

        val snapshot = userManager.getUserData()
        val hasTriggered = snapshot?.callFeedbackHasTriggered ?: false
        if (hasTriggered) return false

        val currentCount = readBlocking({ it[AppStateKeys.CALL_COUNT] }, 0) + 1
        val threshold = snapshot?.callFeedbackRandomThreshold ?: 3

        if ((currentCount >= threshold) || isForce) {
            // Persist hasTriggered=true synchronously BEFORE advancing CALL_COUNT so a
            // crash between the two writes leaves hasTriggered durably set — the next
            // session sees hasTriggered=true and won't re-show the feedback dialog.
            // Develop wrote both keys in one atomic SP edit; this preserves that
            // crash-consistency guarantee now that they live in different DataStores.
            userManager.update(commit = true) { callFeedbackHasTriggered = true }
            writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = currentCount }
            return true
        }

        writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = currentCount }
        return false
    }
}

