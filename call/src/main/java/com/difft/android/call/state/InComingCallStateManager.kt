package com.difft.android.call.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理来电相关的全局状态
 * 替代 LIncomingCallActivity companion object 中的全局状态，提供更好的状态管理和线程安全
 */
@Singleton
class InComingCallStateManager @Inject constructor() {

    // 应用锁相关状态
    private val _needAppLock = AtomicBoolean(true)

    fun setNeedAppLock(value: Boolean) {
        _needAppLock.set(value)
    }

    // Activity是否正在显示
    private val _isActivityShowing = MutableStateFlow(false)
    val isActivityShowing: StateFlow<Boolean> = _isActivityShowing.asStateFlow()

    fun setIsActivityShowing(value: Boolean) {
        _isActivityShowing.value = value
    }

    // 是否在前台
    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    fun setIsInForeground(value: Boolean) {
        _isInForeground.value = value
    }

    // 当前房间ID
    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun setCurrentRoomId(roomId: String?) {
        _currentRoomId.value = roomId
    }

    fun getCurrentRoomId(): String? = _currentRoomId.value

    /**
     * 重置所有状态（在Activity销毁时调用）
     */
    fun reset() {
        _needAppLock.set(true)
        _isActivityShowing.value = false
        _isInForeground.value = false
        _currentRoomId.value = null
    }

    /**
     * 便捷方法：检查Activity是否正在显示（用于向后兼容）
     * 使用公共 StateFlow 属性确保线程安全访问
     */
    fun isActivityShowing(): Boolean = isActivityShowing.value

    /**
     * 便捷方法：检查是否在前台（用于向后兼容）
     * 使用公共 StateFlow 属性确保线程安全访问
     */
    fun isInForeground(): Boolean = isInForeground.value

    /**
     * 便捷方法：检查是否需要应用锁（用于向后兼容）
     */
    fun isNeedAppLock(): Boolean = _needAppLock.get()
}
