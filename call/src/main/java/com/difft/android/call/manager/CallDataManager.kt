package com.difft.android.call.manager

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话数据管理器
 * 负责管理所有通话数据（CallData）的增删改查和状态管理
 * 
 * 主要职责：
 * - 维护通话列表状态
 * - 提供通话数据的查询接口
 * - 管理通话状态（如 isInCalling、notifying 等）
 * - 提供线程安全的状态访问
 */
@Singleton
class CallDataManager @Inject constructor() {

    /**
     * 通话列表状态流
     * 使用 StateFlow 替代 RxJava 的 BehaviorSubject，提供更好的协程集成
     */
    private val _callingList = MutableStateFlow<Map<String, CallData>>(emptyMap())
    
    /**
     * 公开的只读状态流，供外部观察通话列表变化
     */
    val callingList: StateFlow<Map<String, CallData>> = _callingList.asStateFlow()

    /**
     * 更新通话列表数据
     * @param call 新的通话列表，如果为空则清空列表
     */
    fun updateCallingListData(call: Map<String, CallData>) {
        _callingList.value = if (call.isNotEmpty()) call else emptyMap()
    }

    /**
     * 获取当前通话列表数据（用于向后兼容）
     * @return 当前通话列表的副本
     */
    fun getCallListData(): Map<String, CallData> {
        return _callingList.value
    }

    /**
     * 根据会话ID获取通话数据
     * @param conversationId 会话ID
     * @return 匹配的通话数据，如果不存在则返回 null
     */
    fun getCallDataByConversationId(conversationId: String): CallData? {
        return _callingList.value.values.firstOrNull { 
            it.conversation == conversationId && it.type != CallType.INSTANT.type 
        }
    }

    /**
     * 根据房间ID获取通话数据
     * @param roomId 房间ID
     * @return 匹配的通话数据，如果不存在则返回 null
     */
    fun getCallData(roomId: String?): CallData? {
        if (roomId.isNullOrEmpty()) return null
        return _callingList.value[roomId]
    }

    /**
     * 添加通话数据
     * @param call 要添加的通话数据
     */
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

    /**
     * 移除通话数据
     * @param roomId 要移除的房间ID
     */
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

    /**
     * 清空所有通话数据
     */
    fun clearAllCallData() {
        _callingList.value = emptyMap()
        L.d { "[Call] CallDataManager Cleared all call data" }
    }

    /**
     * 检查是否有正在通知的通话数据
     * @return true 如果有正在通知的通话，否则返回 false
     */
    fun hasCallDataNotifying(): Boolean {
        return _callingList.value.values.any { it.notifying == true }
    }

    /**
     * 设置通话的通知状态
     * @param roomId 房间ID
     * @param notifying 是否正在通知
     */
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

    /**
     * 获取当前会议通知状态
     */
    fun getCallNotifyStatus(roomId: String): Boolean {
        return _callingList.value[roomId]?.notifying ?: false
    }

    /**
     * 更新通话状态
     * @param roomId 房间ID
     * @param isInCalling 是否正在通话中
     */
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

