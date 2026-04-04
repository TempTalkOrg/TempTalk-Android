package com.difft.android.push

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FCM 初始化结果状态
 */
sealed class FcmInitResult {
    /** 初始状态，尚未开始 */
    data object Idle : FcmInitResult()

    /** 正在初始化中 */
    data object Loading : FcmInitResult()

    /** Google Play Services 不可用 */
    data class PlayServicesUnavailable(val statusCode: Int) : FcmInitResult()

    /** FCM token 获取成功 */
    data class Success(val token: String) : FcmInitResult()

    /** FCM token 获取失败或超时 */
    data class Failure(val reason: String) : FcmInitResult()
}

/**
 * Push 工具类（F-Droid 构建版本）
 * FCM 在此构建中不可用，消息通知依赖 WebSocket 后台长连接实现。
 */
@Singleton
class PushUtil @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _fcmInitResult = MutableStateFlow<FcmInitResult>(FcmInitResult.Idle)
    val fcmInitResult: StateFlow<FcmInitResult> = _fcmInitResult.asStateFlow()

    private var dialogShownInSession = false

    fun canShowFcmUnavailableDialog(): Boolean = !dialogShownInSession

    fun markFcmUnavailableDialogShown() {
        dialogShownInSession = true
    }

    fun initFCMPush() {
        L.d { "[Push] FCM not available in this build, will prompt background connection" }
        // 触发现有的 handleFcmUnavailable() 流程，引导用户开启后台连接
        _fcmInitResult.value = FcmInitResult.PlayServicesUnavailable(0)
    }

    suspend fun sendRegistrationToServer(fcmID: String) {
        L.d { "[Push] FCM not available in this build" }
    }
}