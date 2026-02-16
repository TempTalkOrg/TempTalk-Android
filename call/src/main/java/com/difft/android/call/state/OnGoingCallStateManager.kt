package com.difft.android.call.state

import com.difft.android.base.call.CallActionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理通话相关的全局状态
 * 替代 LCallActivity companion object 中的全局状态，提供更好的状态管理和线程安全
 */
@Singleton
class OnGoingCallStateManager @Inject constructor() {

    // 应用锁相关状态
    private val _needAppLock = AtomicBoolean(true)
    val needAppLock: Boolean
        get() = _needAppLock.get()

    fun setNeedAppLock(value: Boolean) {
        _needAppLock.set(value)
    }

    // 是否在前台
    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    fun setIsInForeground(value: Boolean) {
        _isInForeground.value = value
    }

    // 是否在通话中
    private val _isInCalling = MutableStateFlow(false)
    val isInCalling: StateFlow<Boolean> = _isInCalling.asStateFlow()

    fun setIsInCalling(value: Boolean) {
        _isInCalling.value = value
    }

    // 是否正在结束通话
    private val _isInCallEnding = MutableStateFlow(false)
    val isInCallEnding: StateFlow<Boolean> = _isInCallEnding.asStateFlow()

    fun setIsInCallEnding(value: Boolean) {
        _isInCallEnding.value = value
    }

    // 当前房间ID
    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun setCurrentRoomId(roomId: String?) {
        _currentRoomId.value = roomId
    }

    fun getCurrentRoomId(): String? = _currentRoomId.value

    // 当前会话ID
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    fun setConversationId(conversationId: String?) {
        _conversationId.value = conversationId
    }

    fun getConversationId(): String? = _conversationId.value


    // 是否处在PIP
    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    fun setIsInPipMode(value: Boolean) {
        _isInPipMode.value = value
    }

    // 是否处在显示屏幕共享
    private val _isInScreenSharing = MutableStateFlow(false)
    val isInScreenSharing: StateFlow<Boolean> = _isInScreenSharing.asStateFlow()

    fun setIsInScreenSharing(value: Boolean) {
        _isInScreenSharing.value = value
    }

    // 当前会议类型
    private val _callType = MutableStateFlow("")
    val callType: StateFlow<String> = _callType.asStateFlow()

    fun setCallType(value: String) {
        _callType.value = value
    }

    // 通话时长显示
    private val _callingTime = MutableStateFlow<Pair<String, String>?>(null)
    val callingTime: StateFlow<Pair<String, String>?> = _callingTime.asStateFlow()

    /**
     * 更新通话时长显示
     * @param roomId 房间ID
     * @param callingTime 显示的时间字符串
     */
    fun updateCallingTime(roomId: String, callingTime: String) {
        _callingTime.value = Pair(roomId, callingTime)
    }

    /**
     * 重置通话时长显示
     */
    fun resetCallingTime() {
        _callingTime.value = null
    }

    /**
     * 获取指定房间的通话时长
     * @param roomId 房间ID
     * @return 通话时长字符串，如果不存在则返回 null
     */
    fun getCallingTime(roomId: String): String? {
        return _callingTime.value?.takeIf { it.first == roomId }?.second
    }

    // 聊天头部通话视图可见性
    private val _chatHeaderCallVisibility = MutableStateFlow(false)
    val chatHeaderCallVisibility: StateFlow<Boolean> = _chatHeaderCallVisibility.asStateFlow()

    /**
     * 设置聊天头部通话视图的可见性
     * @param visibility true 表示可见，false 表示隐藏
     */
    fun setChatHeaderCallVisibility(visibility: Boolean) {
        _chatHeaderCallVisibility.value = visibility
    }

    /**
     * 控制消息数据类
     * 包含操作类型和房间ID，用于在组件之间传递控制指令
     */
    data class ControlMessage(
        val actionType: CallActionType,
        val roomId: String
    )

    // 控制消息状态流
    private val _controlMessage = MutableStateFlow<ControlMessage?>(null)
    val controlMessage: StateFlow<ControlMessage?> = _controlMessage.asStateFlow()

    /**
     * 更新控制消息
     * 
     * @param message 控制消息，null 表示清空
     */
    fun updateControlMessage(message: ControlMessage?) {
        _controlMessage.value = message
    }

    /**
     * 清空控制消息
     */
    fun clearControlMessage() {
        _controlMessage.value = null
    }

    /**
     * 重置所有状态（在通话结束时调用）
     */
    fun reset() {
        _needAppLock.set(true)
        _isInForeground.value = false
        _isInCalling.value = false
        _isInCallEnding.value = false
        _currentRoomId.value = null
        _conversationId.value = null
        _isInPipMode.value = false
        _isInScreenSharing.value = false
        _callType.value = ""
        _callingTime.value = null
        _chatHeaderCallVisibility.value = false
        _controlMessage.value = null
    }

    /**
     * 便捷方法：检查是否在通话中（用于向后兼容）
     */
    fun isInCalling(): Boolean = _isInCalling.value

    /**
     * 便捷方法：检查是否正在结束通话（用于向后兼容）
     */
    fun isInCallEnding(): Boolean = _isInCallEnding.value

    /**
     * 便捷方法：检查是否正在前台
     */
    fun isInForeground(): Boolean = _isInForeground.value

    /**
     * 便捷方法：检查是否正在PIP模式
     */
    fun isInPipMode(): Boolean = _isInPipMode.value

    /**
     * 便捷方法：检查是否正在显示屏幕共享
     */
    fun isInScreenSharing(): Boolean = _isInScreenSharing.value

    /**
     * 便捷方法：获取当前会议类型
     */
    fun callType(): String = _callType.value

}
