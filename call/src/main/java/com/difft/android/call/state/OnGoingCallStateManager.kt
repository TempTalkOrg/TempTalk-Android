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








@Singleton
class OnGoingCallStateManager @Inject constructor() {

    private val _needAppLock = AtomicBoolean(true)
    val needAppLock: Boolean
        get() = _needAppLock.get()

    fun setNeedAppLock(value: Boolean) {
        _needAppLock.set(value)
    }

    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    fun setIsInForeground(value: Boolean) {
        _isInForeground.value = value
    }

    private val _isInCalling = MutableStateFlow(false)
    val isInCalling: StateFlow<Boolean> = _isInCalling.asStateFlow()

    private val _isInCallEnding = MutableStateFlow(false)
    val isInCallEnding: StateFlow<Boolean> = _isInCallEnding.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun getCurrentRoomId(): String? = _currentRoomId.value

    
    private val _clientCallId = MutableStateFlow<String?>(null)
    val clientCallId: StateFlow<String?> = _clientCallId.asStateFlow()

    fun getClientCallId(): String? = _clientCallId.value

    
    
    
    private val _initiatorPreConnectCancelled = AtomicBoolean(false)

    fun isInitiatorPreConnectCancelled(): Boolean = _initiatorPreConnectCancelled.get()

    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    fun getConversationId(): String? = _conversationId.value


    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    fun setIsInPipMode(value: Boolean) {
        _isInPipMode.value = value
    }

    private val _isInScreenSharing = MutableStateFlow(false)
    val isInScreenSharing: StateFlow<Boolean> = _isInScreenSharing.asStateFlow()

    fun setIsInScreenSharing(value: Boolean) {
        _isInScreenSharing.value = value
    }

    private val _callType = MutableStateFlow("")
    val callType: StateFlow<String> = _callType.asStateFlow()

    private val _callingTime = MutableStateFlow<Pair<String, String>?>(null)
    val callingTime: StateFlow<Pair<String, String>?> = _callingTime.asStateFlow()

    



    fun getCallingTime(roomId: String): String? {
        return _callingTime.value?.takeIf { it.first == roomId }?.second
    }

    






    private val _callConnectedAt = MutableStateFlow(0L)
    val callConnectedAt: StateFlow<Long> = _callConnectedAt.asStateFlow()

    fun callConnectedAt(): Long = _callConnectedAt.value

    




    private val _callStatus = MutableStateFlow(CallStatus.UNKNOWN)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _chatHeaderCallVisibility = MutableStateFlow(false)
    val chatHeaderCallVisibility: StateFlow<Boolean> = _chatHeaderCallVisibility.asStateFlow()

    fun setChatHeaderCallVisibility(visibility: Boolean) {
        _chatHeaderCallVisibility.value = visibility
    }

    data class ControlMessage(
        val actionType: CallActionType,
        val roomId: String
    )

    private val _controlMessage = MutableStateFlow<ControlMessage?>(null)
    val controlMessage: StateFlow<ControlMessage?> = _controlMessage.asStateFlow()

    internal fun updateControlMessage(message: ControlMessage?) {
        _controlMessage.value = message
    }

    fun clearControlMessage() {
        _controlMessage.value = null
    }

    
    @Volatile private var ownerSessionId: Long = NO_SESSION
    private val writer = MirrorWriter()

    
    internal fun ownerSession(): Long? = ownerSessionId.takeIf { it != NO_SESSION }

    






    internal fun bindSession(sessionId: Long) {
        val previous = ownerSessionId
        if (previous == sessionId) return
        
        
        ownerSessionId = sessionId
        if (previous != NO_SESSION) {
            L.i { "[Call][Projection] bind takeover: session=$sessionId previous=$previous, resetting session fields" }
            resetSessionFields()
        }
    }

    




    internal fun publish(sessionId: Long, block: MirrorWriter.() -> Unit): Boolean {
        if (!owns(sessionId)) return false
        writer.block()
        return true
    }

    





    internal fun endPresence(sessionId: Long): Boolean {
        if (!owns(sessionId)) return false
        resetSessionFields()
        _needAppLock.set(true)
        _chatHeaderCallVisibility.value = false
        ownerSessionId = NO_SESSION
        return true
    }

    
    private var callPageCount = 0

    
    @MainThread
    fun callPageAttached() {
        callPageCount++
    }

    




    @MainThread
    fun callPageDetached() {
        callPageCount = (callPageCount - 1).coerceAtLeast(0)
        if (callPageCount > 0) return
        _isInForeground.value = false
        _isInPipMode.value = false
        _isInScreenSharing.value = false
    }

    private fun owns(sessionId: Long) = sessionId != NO_SESSION && sessionId == ownerSessionId

    
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

        
        fun updateCallingTime(roomId: String, callingTime: String) {
            _callingTime.value = Pair(roomId, callingTime)
        }

        





        fun markCallConnected(): Long {
            if (_callConnectedAt.value == 0L) _callConnectedAt.value = System.currentTimeMillis()
            return _callConnectedAt.value
        }

        fun updateCallStatus(status: CallStatus) {
            _callStatus.value = status
        }
    }

    
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

    


    fun hasOtherCallData(callingList: Map<String, CallData>): Boolean {
        if (_chatHeaderCallVisibility.value) return true
        return if (!_isInCalling.value) callingList.isNotEmpty()
        else callingList.values.any { it.roomId != getCurrentRoomId() }
    }

    fun isInCalling(): Boolean = _isInCalling.value

    fun isInCallEnding(): Boolean = _isInCallEnding.value

    fun isInForeground(): Boolean = _isInForeground.value

    fun isInPipMode(): Boolean = _isInPipMode.value

    fun isInScreenSharing(): Boolean = _isInScreenSharing.value

    fun callType(): String = _callType.value

    private companion object {
        const val NO_SESSION = -1L
    }
}
