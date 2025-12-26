package com.difft.android.call.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理关键提醒相关的全局状态
 * 替代 CriticalAlertActivity companion object 中的全局状态，提供更好的状态管理和线程安全
 */
@Singleton
class CriticalAlertStateManager @Inject constructor() {

    // 用于保护状态快照读取和修改的锁，确保原子性
    private val stateLock = ReentrantLock()

    // Activity是否正在显示
    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    fun setIsShowing(value: Boolean) {
        stateLock.withLock {
            _isShowing.value = value
        }
    }

    // 是否正在加入会议
    private val _isJoining = MutableStateFlow(false)
    val isJoining: StateFlow<Boolean> = _isJoining.asStateFlow()

    fun setIsJoining(value: Boolean) {
        stateLock.withLock {
            _isJoining.value = value
        }
    }

    // 当前会话ID
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    fun setConversationId(conversationId: String?) {
        stateLock.withLock {
            _conversationId.value = conversationId
        }
    }

    fun getConversationId(): String? = _conversationId.value

    // 声音播放相关状态
    // 当前通知ID
    private val _currentNotificationId = MutableStateFlow<Int?>(null)
    val currentNotificationId: StateFlow<Int?> = _currentNotificationId.asStateFlow()

    fun setCurrentNotificationId(notificationId: Int?) {
        _currentNotificationId.value = notificationId
    }

    fun getCurrentNotificationId(): Int? = _currentNotificationId.value

    // 是否正在播放声音
    private val _isPlayingSound = MutableStateFlow(false)
    val isPlayingSound: StateFlow<Boolean> = _isPlayingSound.asStateFlow()

    fun setIsPlayingSound(value: Boolean) {
        stateLock.withLock {
            _isPlayingSound.value = value
        }
    }

    // 当前播放token（用于防止并发竞争）
    private val _currentPlayToken = MutableStateFlow(0L)
    val currentPlayToken: StateFlow<Long> = _currentPlayToken.asStateFlow()

    fun setCurrentPlayToken(token: Long) {
        _currentPlayToken.value = token
    }

    fun getCurrentPlayToken(): Long = _currentPlayToken.value

    /**
     * 重置所有状态（在Activity销毁时调用）
     */
    fun reset() {
        stateLock.withLock {
            _isShowing.value = false
            _conversationId.value = null
            _currentNotificationId.value = null
            _isPlayingSound.value = false
            _isJoining.value = false
        }
    }

    /**
     * 重置声音播放相关状态
     */
    fun resetSoundState() {
        stateLock.withLock {
            _currentNotificationId.value = null
            _isPlayingSound.value = false
        }
    }

    /**
     * 便捷方法：检查Activity是否正在显示（用于向后兼容）
     * 使用公共 StateFlow 属性确保线程安全访问
     */
    fun isShowing(): Boolean = _isShowing.value

    /**
     * 便捷方法：检查是否正在加入会议
     */
    fun isJoining(): Boolean = _isJoining.value

    /**
     * 便捷方法：检查是否正在播放声音（用于向后兼容）
     */
    fun isPlayingSound(): Boolean = _isPlayingSound.value

    /**
     * 状态快照数据类，用于原子性获取多个状态值
     */
    data class StateSnapshot(
        val isPlayingSound: Boolean,
        val isShowing: Boolean,
        val conversationId: String?
    )

    /**
     * 原子性地获取所有相关状态的快照
     * 使用锁确保在读取三个 StateFlow 值期间，其他线程不能修改状态
     * 用于确保在并发更新时状态检查的一致性
     */
    fun getStateSnapshot(): StateSnapshot {
        return stateLock.withLock {
            StateSnapshot(
                isPlayingSound = _isPlayingSound.value,
                isShowing = _isShowing.value,
                conversationId = _conversationId.value
            )
        }
    }
}