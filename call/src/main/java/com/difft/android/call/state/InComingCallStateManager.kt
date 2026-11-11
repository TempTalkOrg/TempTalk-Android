package com.difft.android.call.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class InComingCallStateManager @Inject constructor() {

    private val _needAppLock = AtomicBoolean(true)

    fun setNeedAppLock(value: Boolean) {
        _needAppLock.set(value)
    }

    private val _isActivityShowing = MutableStateFlow(false)
    val isActivityShowing: StateFlow<Boolean> = _isActivityShowing.asStateFlow()

    fun setIsActivityShowing(value: Boolean) {
        _isActivityShowing.value = value
    }

    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    fun setIsInForeground(value: Boolean) {
        _isInForeground.value = value
    }

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun setCurrentRoomId(roomId: String?) {
        _currentRoomId.value = roomId
    }

    fun getCurrentRoomId(): String? = _currentRoomId.value

    


    fun reset() {
        _needAppLock.set(true)
        _isActivityShowing.value = false
        _isInForeground.value = false
        _currentRoomId.value = null
    }

    fun isActivityShowing(): Boolean = isActivityShowing.value

    fun isInForeground(): Boolean = isInForeground.value

    fun isNeedAppLock(): Boolean = _needAppLock.get()
}
