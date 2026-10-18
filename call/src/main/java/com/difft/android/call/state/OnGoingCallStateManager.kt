package com.difft.android.call.state

import androidx.annotation.MainThread
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.CallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理通话相关的全局状态
 * 替代 LCallActivity companion object 中的全局状态，提供更好的状态管理和线程安全
 *
 * The call's session fields (presence, ending, room id, call identity, type, duration, status) are
 * a projection of the live call session: they have public reads only and are written through
 * [publish], which runs only for the session that owns the projection (the newest one to call
 * [bindSession]). The window/UI flags keep their public setters. `controlMessage` is the
 * incoming-call page's signal: written by the call message handler, cleared by that page.
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

    // 是否正在结束通话
    private val _isInCallEnding = MutableStateFlow(false)
    val isInCallEnding: StateFlow<Boolean> = _isInCallEnding.asStateFlow()

    // 当前房间ID
    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun getCurrentRoomId(): String? = _currentRoomId.value

    // 客户端本次发起生成的 clientCallId。仅主叫发起路径写入，贯穿整个通话会话，
    // 供 roomId 尚未就绪（窗口 W）时的取消/挂断作为服务端回落定位键。
    private val _clientCallId = MutableStateFlow<String?>(null)
    val clientCallId: StateFlow<String?> = _clientCallId.asStateFlow()

    fun getClientCallId(): String? = _clientCallId.value

    // 主叫在信令未连上（窗口 W）就退出的取消意图门闩。CallExitHandler 在决定取消的第一刻
    // 同步置位；CallSessionStarter 拿到建房响应后据此跳过响铃/始通话消息，并用已知 roomId
    // 补发权威取消——保证无论"取消"与"建房完成"谁先跑，取消都能赢。
    // 生命周期：起点由 setClientCallId 清零、终点由 endPresence() 或另一会话 bindSession() 接管时清零，杜绝跨通污染。
    private val _initiatorPreConnectCancelled = AtomicBoolean(false)

    fun isInitiatorPreConnectCancelled(): Boolean = _initiatorPreConnectCancelled.get()

    // 当前会话ID
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

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

    // 通话时长显示
    private val _callingTime = MutableStateFlow<Pair<String, String>?>(null)
    val callingTime: StateFlow<Pair<String, String>?> = _callingTime.asStateFlow()

    /**
     * 获取指定房间的通话时长
     * @param roomId 房间ID
     * @return 通话时长字符串，如果不存在则返回 null
     */
    fun getCallingTime(roomId: String): String? {
        return _callingTime.value?.takeIf { it.first == roomId }?.second
    }

    /**
     * Wall-clock instant the duration timer started, i.e. when the call counts as connected;
     * 0 while still connecting. The ongoing-call notification publishes it as `when`, which is
     * what the system status-bar call chip counts up from — left at its default, `when` is
     * re-stamped on every rebuild and the chip restarts from zero. Shared through this singleton
     * because the notification is built inside ForegroundService, outside the call's UI scope.
     */
    private val _callConnectedAt = MutableStateFlow(0L)
    val callConnectedAt: StateFlow<Long> = _callConnectedAt.asStateFlow()

    fun callConnectedAt(): Long = _callConnectedAt.value

    /**
     * Mirrors the active call's status into singleton scope. The foreground service builds the
     * ongoing-call notification and lives outside the call screen's ViewModel, so this is how it
     * learns which state to render without being poked by Activity lifecycle callbacks.
     */
    private val _callStatus = MutableStateFlow(CallStatus.UNKNOWN)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

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
    internal fun updateControlMessage(message: ControlMessage?) {
        _controlMessage.value = message
    }

    /**
     * 清空控制消息
     */
    fun clearControlMessage() {
        _controlMessage.value = null
    }

    // Written on main (bind/endPresence), read by any-thread publishes.
    @Volatile private var ownerSessionId: Long = NO_SESSION
    private val writer = MirrorWriter()

    /**
     * [sessionId] owns the projection from now on; the newest session to bind wins. Taking over from
     * a different live owner first resets the session fields (keeping `controlMessage` and the
     * window/UI flags), so nothing the previous session wrote survives into the new one even when
     * the previous page's [endPresence] runs late or never. Re-binding the current owner is a no-op,
     * which keeps the fields across a configuration-change re-create.
     */
    internal fun bindSession(sessionId: Long) {
        val previous = ownerSessionId
        if (previous == sessionId) return
        // Owner first, then reset: a publish from the previous owner that starts after this point
        // is rejected by owns(), so it cannot land on top of the cleared fields.
        ownerSessionId = sessionId
        if (previous != NO_SESSION) {
            L.i { "[Call][Projection] bind takeover: session=$sessionId previous=$previous, resetting session fields" }
            resetSessionFields()
        }
    }

    /**
     * Runs [block] only while [sessionId] owns the projection; returns whether it ran. The owner check
     * and the write are not atomic: a write racing a re-bind or [endPresence] can land once.
     * No lock, because the writes may resume collectors inline.
     */
    internal fun publish(sessionId: Long, block: MirrorWriter.() -> Unit): Boolean {
        if (!owns(sessionId)) return false
        writer.block()
        return true
    }

    /**
     * The call session ended: resets the session fields, except `controlMessage` (the
     * incoming-call page's), restores `needAppLock` and hides the chat-header call view, then
     * releases ownership so the session's later writes are dropped. The page flags belong to the
     * call pages ([callPageDetached]). A no-op returning false when another session owns the projection.
     */
    internal fun endPresence(sessionId: Long): Boolean {
        if (!owns(sessionId)) return false
        resetSessionFields()
        _needAppLock.set(true)
        _chatHeaderCallVisibility.value = false
        ownerSessionId = NO_SESSION
        return true
    }

    // Live call-page view models across all sessions; main-confined.
    private var callPageCount = 0

    /** A call page's view model was created; a configuration change keeps it and never calls this again. */
    @MainThread
    fun callPageAttached() {
        callPageCount++
    }

    /**
     * A call page's view model was cleared. When none is left, the page flags (`isInForeground`,
     * `isInPipMode`, `isInScreenSharing`) are reset: no page is left to write or clear them.
     * Counted across sessions, so an older page going away never clears a newer page's flags.
     */
    @MainThread
    fun callPageDetached() {
        callPageCount = (callPageCount - 1).coerceAtLeast(0)
        if (callPageCount > 0) return
        _isInForeground.value = false
        _isInPipMode.value = false
        _isInScreenSharing.value = false
    }

    private fun owns(sessionId: Long) = sessionId != NO_SESSION && sessionId == ownerSessionId

    /** The projection's setters; reachable only through [publish]. */
    internal inner class MirrorWriter {
        fun setIsInCalling(value: Boolean) {
            _isInCalling.value = value
        }

        fun setIsInCallEnding(value: Boolean) {
            _isInCallEnding.value = value
        }

        fun setCurrentRoomId(roomId: String?) {
            _currentRoomId.value = roomId
        }

        fun setClientCallId(clientCallId: String?) {
            _clientCallId.value = clientCallId
            // 新去电会话身份建立 = 起点清零门闩，作为 endPresence() 之外的第二道保险：即使某次结束时
            // endPresence() 未跑到（@Singleton 状态可能残留），也不会用上一通的取消意图污染这一通去电。
            _initiatorPreConnectCancelled.set(false)
        }

        fun markInitiatorPreConnectCancelled() {
            _initiatorPreConnectCancelled.set(true)
        }

        fun setConversationId(conversationId: String?) {
            _conversationId.value = conversationId
        }

        fun setCallType(value: String) {
            _callType.value = value
        }

        /** 更新通话时长显示：[callingTime] 为房间 [roomId] 显示的时间字符串 */
        fun updateCallingTime(roomId: String, callingTime: String) {
            _callingTime.value = Pair(roomId, callingTime)
        }

        /**
         * Idempotent within a session: reconnects and server switches must not re-stamp a connected
         * call. Returns the connect instant in force afterwards, the value the notification publishes
         * as `when`. The instant is cleared whenever ownership ends ([endPresence]) or passes to
         * another session ([bindSession]), so a new session always stamps its own.
         */
        fun markCallConnected(): Long {
            if (_callConnectedAt.value == 0L) _callConnectedAt.value = System.currentTimeMillis()
            return _callConnectedAt.value
        }

        fun updateCallStatus(status: CallStatus) {
            _callStatus.value = status
        }
    }

    /** The fields a session writes through [publish]; `controlMessage` and the window/UI flags excluded. */
    private fun resetSessionFields() {
        _isInCalling.value = false
        _isInCallEnding.value = false
        _currentRoomId.value = null
        _clientCallId.value = null
        _initiatorPreConnectCancelled.set(false)
        _conversationId.value = null
        _callType.value = ""
        _callingTime.value = null
        _callConnectedAt.value = 0L
        _callStatus.value = CallStatus.UNKNOWN
    }

    /**
     * 判断是否存在需要展示通话头部栏的其他通话数据。
     * 当用户正在通话时，排除自身 roomId 的通话，仅考虑其他通话。
     */
    fun hasOtherCallData(callingList: Map<String, CallData>): Boolean {
        if (_chatHeaderCallVisibility.value) return true
        return if (!_isInCalling.value) callingList.isNotEmpty()
        else callingList.values.any { it.roomId != getCurrentRoomId() }
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

    private companion object {
        const val NO_SESSION = -1L
    }
}
