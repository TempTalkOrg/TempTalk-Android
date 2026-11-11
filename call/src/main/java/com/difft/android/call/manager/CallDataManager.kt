package com.difft.android.call.manager

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CallDataManager @Inject constructor() {

    private val _callingList = MutableStateFlow<Map<String, CallData>>(emptyMap())

    val callingList: StateFlow<Map<String, CallData>> = _callingList.asStateFlow()

    

    fun updateCallingListData(call: Map<String, CallData>) {
        _callingList.value = if (call.isNotEmpty()) call else emptyMap()
    }

    
    fun getCallListData(): Map<String, CallData> {
        return _callingList.value
    }

    fun getCallDataByConversationId(conversationId: String): CallData? {
        return _callingList.value.values.firstOrNull {
            it.conversation == conversationId && it.type != CallType.INSTANT.type
        }
    }

    fun getCallData(roomId: String?): CallData? {
        if (roomId.isNullOrEmpty()) return null
        return _callingList.value[roomId]
    }

    fun addCallData(call: CallData) {
        if (call.roomId.isEmpty()) {
            L.w { "[Call] CallDataManager Cannot add call data with empty roomId" }
            return
        }

        val current = _callingList.value.toMutableMap()
        if (!current.containsKey(call.roomId)) {
            current[call.roomId] = call
            _callingList.value = current
            L.d { "[Call] CallDataManager Added call data for roomId: ${call.roomId}" }
        } else {
            L.d { "[Call] CallDataManager Call data already exists for roomId: ${call.roomId}" }
        }
    }

    fun removeCallData(roomId: String?) {
        if (roomId.isNullOrEmpty()) {
            return
        }

        val current = _callingList.value.toMutableMap()
        if (current.remove(roomId) != null) {
            _callingList.value = current
            L.d { "[Call] CallDataManager Removed call data for roomId: $roomId" }
        }
    }

    fun clearAllCallData() {
        _callingList.value = emptyMap()
        L.d { "[Call] CallDataManager Cleared all call data" }
    }

    fun hasCallDataNotifying(): Boolean {
        return _callingList.value.values.any { it.notifying == true }
    }

    fun setCallNotifyStatus(roomId: String, notifying: Boolean) {
        val current = _callingList.value.toMutableMap()
        current[roomId]?.let { callData ->
            callData.notifying = notifying
            _callingList.value = current
            L.d { "[Call] CallDataManager Set notify status for roomId: $roomId, notifying: $notifying" }
        } ?: run {
            L.w { "[Call] CallDataManager Cannot set notify status: call data not found for roomId: $roomId" }
        }
    }

    fun getCallNotifyStatus(roomId: String): Boolean {
        return _callingList.value[roomId]?.notifying ?: false
    }

    fun updateCallingState(roomId: String, isInCalling: Boolean) {
        val current = _callingList.value.toMutableMap()
        current[roomId]?.let { callData ->
            callData.isInCalling = isInCalling
            _callingList.value = current
            L.d { "[Call] CallDataManager Updated calling state for roomId: $roomId, isInCalling: $isInCalling" }
        } ?: run {
            L.w { "[Call] CallDataManager Cannot update calling state: call data not found for roomId: $roomId" }
        }
    }
}

