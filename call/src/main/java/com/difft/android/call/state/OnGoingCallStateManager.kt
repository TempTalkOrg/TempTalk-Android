package com.difft.android.call.state

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
    }

    /**
     * 便捷方法：检查是否在通话中（用于向后兼容）
     */
    fun isInCalling(): Boolean = _isInCalling.value

    /**
     * 便捷方法：检查是否正在结束通话（用于向后兼容）
     */
    fun isInCallEnding(): Boolean = _isInCallEnding.value
}
