package com.difft.android.call.manager

import com.difft.android.base.utils.globalServices

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CallTimeoutManager @Inject constructor(
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
) {
    private val callService by lazy {
        callHttpClient.get().getService(LCallHttpService::class.java)
    }

    private val timeoutJobMap = mutableMapOf<String, Job>()

    companion object {
        const val DEF_INCOMING_CALL_TIMEOUT = 56L 
        const val DEF_ONGOING_CALL_TIMEOUT = 60L 
        const val DEF_LEAVE_CALL_TIMEOUT = 60L 
        private const val INCOMING_CALL_CHECK_INTERVAL = 5L 
    }

    enum class CallState {
        ONGOING_CALL,
        INCOMING_CALL,
        LEAVE_CALL
    }

    




    fun checkCallWithTimeout(
        callState: CallState,
        timeoutSeconds: Long,
        roomId: String,
        callBack: (Boolean) -> Unit
    ) {
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

    fun cancelCallWithTimeout(roomId: String) {
        timeoutJobMap.remove(roomId)?.cancel()
    }

    
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

    
    private fun startIncomingCallTimeout(
        timeoutSeconds: Long,
        roomId: String,
        callBack: (Boolean) -> Unit
    ) {
        val job = appScope.launch {
            val timeoutJob = launch {
                delay(timeoutSeconds * 1000)
                L.i { "[Call] CallTimeoutManager Timeout stop IncomingCall service roomId: $roomId" }
                callBack(true)
            }

            val checkJob = launch {
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

            timeoutJob.join()
            checkJob.cancel()
        }
        timeoutJobMap[roomId] = job
    }

    private suspend fun checkCallStatus(roomId: String, callBack: (Boolean) -> Unit) {
        try {
            
            if (globalServices.userManager.getUserData()?.microToken.isNullOrEmpty()) {
                L.e { "[Call] CallTimeoutManager checkCallStatus failed: missing authentication token" }
                callBack(false)
                return
            }

            val response = withContext(Dispatchers.IO) {
                callService.checkCall(roomId)
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
            callBack(false)
        }
    }

    fun clearAllTimeouts() {
        timeoutJobMap.values.forEach { it.cancel() }
        timeoutJobMap.clear()
    }
}

