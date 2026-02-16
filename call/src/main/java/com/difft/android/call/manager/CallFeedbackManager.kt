package com.difft.android.call.manager

import android.app.Activity
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.ui.CallRatingFeedbackView
import com.difft.android.call.util.CallComposeUiUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话反馈管理器
 * 负责管理通话反馈相关的逻辑
 */
@Singleton
class CallFeedbackManager @Inject constructor() {
    
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Call
        fun callHttpClient(): ChativeHttpClient
    }
    
    private val callService by lazy {
        val callHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callHttpClient()
        callHttpClient.getService(LCallHttpService::class.java)
    }
    
    // 存储反馈信息
    private var callFeedbackInfo: FeedbackCallInfo? = null
    
    // ==================== 反馈触发逻辑相关常量 ====================
    private companion object {
        private const val RESET_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        private const val KEY_CALL_LAST_FEEDBACK_RESET_TIME = "call_last_feedback_reset_time"
        private const val KEY_CALL_FEEDBACK_RANDOM_THRESHOLD = "call_feedback_random_threshold"
        private const val KEY_CALL_FEEDBACK_HAS_TRIGGERED = "call_feedback_has_triggered"
        private const val KEY_CALL_COUNT = "call_count"
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
        val token = SecureSharedPrefsUtil.getToken()
        if (token.isNullOrEmpty()) {
            L.e { "[Call] CallFeedbackManager submitCallFeedback failed: missing authentication token" }
            return
        }
        
        try {
            val response = withContext(Dispatchers.IO) {
                callService.callFeedback(token, params).await()
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
        val lastReset = SharedPrefsUtil.getLong(KEY_CALL_LAST_FEEDBACK_RESET_TIME)
        val now = System.currentTimeMillis()

        if (now - lastReset >= RESET_INTERVAL_MS || lastReset == 0L) {
            val newThreshold = (1..5).random()
            SharedPrefsUtil.putLong(KEY_CALL_LAST_FEEDBACK_RESET_TIME, now)
            SharedPrefsUtil.putInt(KEY_CALL_FEEDBACK_RANDOM_THRESHOLD, newThreshold)
            SharedPrefsUtil.putBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, false)
            SharedPrefsUtil.putInt(KEY_CALL_COUNT, 0)
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

        val hasTriggered = SharedPrefsUtil.getBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, false)
        if (hasTriggered) return false

        val currentCount = SharedPrefsUtil.getInt(KEY_CALL_COUNT, 0) + 1
        val threshold = SharedPrefsUtil.getInt(KEY_CALL_FEEDBACK_RANDOM_THRESHOLD, 3)

        SharedPrefsUtil.putInt(KEY_CALL_COUNT, currentCount)

        if ((currentCount >= threshold) || isForce) {
            SharedPrefsUtil.putBoolean(KEY_CALL_FEEDBACK_HAS_TRIGGERED, true)
            return true
        }

        return false
    }
}

