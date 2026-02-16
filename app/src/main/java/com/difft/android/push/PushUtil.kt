package com.difft.android.push

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.BindPushTokenRequestBody
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
 * Push 工具类，负责 FCM 初始化和 Token 管理
 * 使用 Dagger 注入，支持协程
 */
@Singleton
class PushUtil @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ChativeHttpClientModule.Chat private val chatHttpClient: ChativeHttpClient
) {
    // FCM 初始化状态 StateFlow (发布所有状态，供其他模块监听)
    private val _fcmInitResult = MutableStateFlow<FcmInitResult>(FcmInitResult.Idle)
    val fcmInitResult: StateFlow<FcmInitResult> = _fcmInitResult.asStateFlow()

    // FCM 不可用弹窗标记（Application 生命周期内只弹一次）
    private var fcmUnavailableDialogShown = false

    /**
     * 检查是否可以显示 FCM 不可用弹窗
     * @return true 表示可以显示，false 表示已经显示过
     */
    fun canShowFcmUnavailableDialog(): Boolean {
        return !fcmUnavailableDialogShown
    }

    /**
     * 标记 FCM 不可用弹窗已显示
     */
    fun markFcmUnavailableDialogShown() {
        fcmUnavailableDialogShown = true
    }

    /**
     * 初始化 FCM Push (非阻塞方式)
     * 成功时自动上报token到服务器
     */
    fun initFCMPush() {
        // 防止重复调用：只有在 Loading 状态时才阻止
        if (_fcmInitResult.value is FcmInitResult.Loading) {
            L.d { "[Push][fcm] Already initializing, skip duplicate call" }
            return
        }

        appScope.launch(Dispatchers.IO) {
            // 检查 Google Play Services 是否可用
            val playServiceStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

            if (playServiceStatus != ConnectionResult.SUCCESS) {
                L.w { "[Push][fcm] Google Play Services not available, status:$playServiceStatus" }
                _fcmInitResult.value = FcmInitResult.PlayServicesUnavailable(playServiceStatus)
                return@launch
            }

            // 发送 Loading 状态
            _fcmInitResult.value = FcmInitResult.Loading

            // Google Play Services 可用，尝试获取 FCM token（15秒超时）
            val tokenResult = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Result<String>> { continuation ->
                    FirebaseMessaging.getInstance().token
                        .addOnCompleteListener { task ->
                            if (continuation.isActive) {
                                if (task.isSuccessful && task.result != null) {
                                    continuation.resume(Result.success(task.result))
                                } else {
                                    val error = task.exception?.message ?: "Unknown error"
                                    continuation.resume(Result.failure(Exception(error)))
                                }
                            }
                        }

                    continuation.invokeOnCancellation {
                        L.d { "[Push][fcm] Token fetch coroutine cancelled" }
                    }
                }
            }

            // 处理结果
            when {
                tokenResult == null -> {
                    L.w { "[Push][fcm] Fetching FCM registration token timeout (15s)" }
                    _fcmInitResult.value = FcmInitResult.Failure("Timeout after 15 seconds")
                }

                tokenResult.isSuccess -> {
                    val token = tokenResult.getOrNull()!!
                    L.i { "[Push][fcm] Fetching FCM registration token success ${token.length}" }

                    // 成功时自动上报token到服务器
                    sendRegistrationToServer(token)

                    // 设置 Firebase Metrics
                    FirebaseMessaging.getInstance().setDeliveryMetricsExportToBigQuery(true)

                    _fcmInitResult.value = FcmInitResult.Success(token)
                }

                else -> {
                    val error = tokenResult.exceptionOrNull()?.message ?: "Unknown error"
                    L.w { "[Push][fcm] Fetching FCM registration token failed: $error" }
                    _fcmInitResult.value = FcmInitResult.Failure(error)
                }
            }
        }
    }

    /**
     * 发送 Push Token 到服务器
     */
    suspend fun sendRegistrationToServer(fcmID: String) {
        withContext(Dispatchers.IO) {
            try {
                // 检查 BasicAuth 是否存在，不存在表示未登录
                val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
                if (TextUtils.isEmpty(basicAuth)) {
                    L.w { "[Push] User not logged in, skip binding token" }
                    return@withContext
                }

                val result = chatHttpClient.httpService.fetchBindPushToken(
                    basicAuth, "fcm_v2", BindPushTokenRequestBody(fcmID)
                ).await()

                if (result.status == 0) {
                    L.i { "[Push] 绑定token成功" }
                } else {
                    L.e { "[Push] 绑定token失败: status=${result.status}" }
                }
            } catch (e: Exception) {
                L.e { "[Push] 绑定token失败: ${e.message}" }
            }
        }
    }
}