package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话超时管理器
 * 负责管理通话超时检测相关的逻辑
 * 
 * 主要职责：
 * - 管理不同类型的通话超时检测（来电、进行中、离开）
 * - 定期检查通话状态（仅用于来电状态）
 * - 超时后执行回调
 */
@Singleton
class CallTimeoutManager @Inject constructor() {
    
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
    
    // 存储每个 roomId 对应的超时检测任务
    private val timeoutJobMap = mutableMapOf<String, Job>()
    
    // 超时常量
    companion object {
        const val DEF_INCOMING_CALL_TIMEOUT = 56L // Seconds
        const val DEF_ONGOING_CALL_TIMEOUT = 60L // Seconds
        const val DEF_LEAVE_CALL_TIMEOUT = 60L // Seconds
        private const val INCOMING_CALL_CHECK_INTERVAL = 5L // Seconds - 来电状态检查间隔
    }
    
    /**
     * 通话状态枚举
     */
    enum class CallState {
        ONGOING_CALL,  // 进行中的通话
        INCOMING_CALL, // 来电
        LEAVE_CALL     // 离开通话
    }
    
    /**
     * 启动超时检测
     * 
     * @param callState 通话状态
     * @param timeoutSeconds 超时时间（秒）
     * @param roomId 房间ID
     * @param callBack 超时回调，参数表示是否应该停止服务
     */
    fun checkCallWithTimeout(
        callState: CallState,
        timeoutSeconds: Long,
        roomId: String,
        callBack: (Boolean) -> Unit
    ) {
        // 取消之前的任务（如果存在）
        cancelCallWithTimeout(roomId)
        
        when (callState) {
            CallState.INCOMING_CALL -> {
                startIncomingCallTimeout(timeoutSeconds, roomId, callBack)
            }
            CallState.ONGOING_CALL, CallState.LEAVE_CALL -> {
                startSimpleTimeout(timeoutSeconds, roomId, callBack)
            }
        }
    }
    
    /**
     * 取消超时检测
     * 
     * @param roomId 房间ID
     */
    fun cancelCallWithTimeout(roomId: String) {
        timeoutJobMap.remove(roomId)?.cancel()
    }
    
    /**
     * 启动简单的超时检测（仅超时，不检查状态）
     * 用于 ONGOING_CALL 和 LEAVE_CALL 状态
     */
    private fun startSimpleTimeout(
        timeoutSeconds: Long,
        roomId: String,
        callBack: (Boolean) -> Unit
    ) {
        val job = appScope.launch {
            delay(timeoutSeconds * 1000)
            L.i { "[Call] CallTimeoutManager Timeout reached for roomId: $roomId" }
            callBack(true)
        }
        timeoutJobMap[roomId] = job
    }
    
    /**
     * 启动来电超时检测（超时 + 定期检查通话状态）
     * 用于 INCOMING_CALL 状态
     */
    private fun startIncomingCallTimeout(
        timeoutSeconds: Long,
        roomId: String,
        callBack: (Boolean) -> Unit
    ) {
        val job = appScope.launch {
            // 启动超时检测
            val timeoutJob = launch {
                delay(timeoutSeconds * 1000)
                L.i { "[Call] CallTimeoutManager Timeout stop IncomingCall service roomId: $roomId" }
                callBack(true)
            }
            
            // 启动定期检查任务
            val checkJob = launch {
                // 使用 Flow 实现定期检查，每 5 秒检查一次
                flow {
                    while (timeoutJob.isActive) {
                        delay(INCOMING_CALL_CHECK_INTERVAL * 1000)
                        emit(Unit)
                    }
                }
                .onEach {
                    checkCallStatus(roomId, callBack)
                }
                .flowOn(Dispatchers.IO)
                .collect()
            }
            
            // 等待超时或检查任务完成
            timeoutJob.join()
            checkJob.cancel()
        }
        timeoutJobMap[roomId] = job
    }
    
    /**
     * 检查通话状态
     * 用于来电状态，定期检查服务器上的通话状态
     * 
     * @param roomId 房间ID
     * @param callBack 回调函数
     */
    private suspend fun checkCallStatus(roomId: String, callBack: (Boolean) -> Unit) {
        try {
            val token = SecureSharedPrefsUtil.getToken()
            if (token.isNullOrEmpty()) {
                L.e { "[Call] CallTimeoutManager checkCallStatus failed: missing authentication token" }
                callBack(false)
                return
            }
            
            val response = withContext(Dispatchers.IO) {
                callService.checkCall(token, roomId).await()
            }
            
            if (response == null) {
                L.e { "[Call] CallTimeoutManager check call response is null" }
                callBack(false)
                return
            }
            
            if (response.status == 0 && (response.data == null || response.data?.userStopped == true || response.data?.anotherDeviceJoined == true)) {
                L.e { "[Call] CallTimeoutManager check call result stopIncomingCallService" }
                callBack(true)
            }
        } catch (error: Exception) {
            L.e { "[Call] CallTimeoutManager check call error: ${error.message}" }
            // response is null
            callBack(false)
        }
    }
    
    /**
     * 清理所有超时检测任务
     */
    fun clearAllTimeouts() {
        timeoutJobMap.values.forEach { it.cancel() }
        timeoutJobMap.clear()
    }
}

