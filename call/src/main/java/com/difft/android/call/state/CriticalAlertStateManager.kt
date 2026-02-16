package com.difft.android.call.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理关键提醒相关的全局状态
 * 替代 CriticalAlertActivity companion object 中的全局状态，提供更好的状态管理和线程安全
 */
@Singleton
class CriticalAlertStateManager @Inject constructor() {

    private val _state = MutableStateFlow(StateSnapshot.initial())
    val state: StateFlow<StateSnapshot> = _state.asStateFlow()

    /**
     * 重置所有状态（在Activity销毁时调用）
     */
    fun reset() {
        updateState { StateSnapshot.initial() }
    }

    /**
     * 重置声音播放相关状态
     */
    fun resetSoundState() {
        updateState { it.copy(currentNotificationId = null, isPlayingSound = false) }
    }

    /**
     * 便捷方法：检查Activity是否正在显示（用于向后兼容）
     * 使用公共 StateFlow 属性确保线程安全访问
     */
    fun isShowing(): Boolean = _state.value.isShowing

    /**
     * 便捷方法：检查是否正在加入会议
     */
    fun isJoining(): Boolean = _state.value.isJoining

    /**
     * 便捷方法：检查是否正在播放声音（用于向后兼容）
     */
    fun isPlayingSound(): Boolean = _state.value.isPlayingSound

    /**
     * 状态快照数据类，用于原子性获取多个状态值
     */
    data class StateSnapshot(
        val isPlayingSound: Boolean,
        val isShowing: Boolean,
        val isJoining: Boolean,
        val conversationId: String?,
        val currentNotificationId: Int?,
        val currentPlayToken: Long
    ) {
        companion object {
            fun initial() = StateSnapshot(
                isPlayingSound = false,
                isShowing = false,
                isJoining = false,
                conversationId = null,
                currentNotificationId = null,
                currentPlayToken = 0L
            )
        }
    }

    /**
     * 原子性地获取所有相关状态的快照
     * 使用 AtomicReference 确保读取到一致的状态快照
     * 用于确保在并发更新时状态检查的一致性
     */
    fun getStateSnapshot(): StateSnapshot = _state.value

    fun setIsShowing(value: Boolean) {
        updateState { it.copy(isShowing = value) }
    }

    fun setIsJoining(value: Boolean) {
        updateState { it.copy(isJoining = value) }
    }

    fun setConversationId(conversationId: String?) {
        updateState { it.copy(conversationId = conversationId) }
    }

    fun getConversationId(): String? = _state.value.conversationId

    fun setCurrentNotificationId(notificationId: Int?) {
        updateState { it.copy(currentNotificationId = notificationId) }
    }

    fun getCurrentNotificationId(): Int? = _state.value.currentNotificationId

    fun setIsPlayingSound(value: Boolean) {
        updateState { it.copy(isPlayingSound = value) }
    }

    fun setCurrentPlayToken(token: Long) {
        updateState { it.copy(currentPlayToken = token) }
    }

    fun getCurrentPlayToken(): Long = _state.value.currentPlayToken

    /**
     * 统一入口：原子更新状态并同步 StateFlow
     */
    fun updateState(block: (StateSnapshot) -> StateSnapshot) {
        _state.update { current ->
            val next = block(current)
            if (current == next) current else next
        }
    }

}