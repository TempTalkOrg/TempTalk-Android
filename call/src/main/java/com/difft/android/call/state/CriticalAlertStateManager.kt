package com.difft.android.call.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class CriticalAlertStateManager @Inject constructor() {

    private val _state = MutableStateFlow(StateSnapshot.initial())
    val state: StateFlow<StateSnapshot> = _state.asStateFlow()

    fun reset() {
        updateState { StateSnapshot.initial() }
    }

    fun resetSoundState() {
        updateState { it.copy(currentNotificationId = null, isPlayingSound = false) }
    }

    fun isShowing(): Boolean = _state.value.isShowing

    fun isJoining(): Boolean = _state.value.isJoining

    fun isPlayingSound(): Boolean = _state.value.isPlayingSound

    data class StateSnapshot(
        val isPlayingSound: Boolean,
        val isShowing: Boolean,
        val isJoining: Boolean,
        val conversationId: String?,
        val currentNotificationId: Int?,
        val currentPlayToken: Long,
        
        val pageNotificationId: Int?,
        
        val isPageVisible: Boolean,
        



        val pageClosingSilentlyId: Int?,
    ) {
        companion object {
            fun initial() = StateSnapshot(
                isPlayingSound = false,
                isShowing = false,
                isJoining = false,
                conversationId = null,
                currentNotificationId = null,
                currentPlayToken = 0L,
                pageNotificationId = null,
                isPageVisible = false,
                pageClosingSilentlyId = null,
            )
        }
    }

    


    fun getStateSnapshot(): StateSnapshot = _state.value

    fun setIsShowing(value: Boolean) {
        updateState { it.copy(isShowing = value) }
    }

    fun isPageVisible(): Boolean = _state.value.isPageVisible

    fun setPageClosingSilently(notificationId: Int) {
        updateState { it.copy(pageClosingSilentlyId = notificationId) }
    }

    fun setPageVisible(value: Boolean) {
        updateState { it.copy(isPageVisible = value) }
    }

    fun getPageNotificationId(): Int? = _state.value.pageNotificationId

    fun setPageNotificationId(notificationId: Int?) {
        updateState { it.copy(pageNotificationId = notificationId) }
    }

    




    fun clearPage(notificationId: Int, conversationId: String?) {
        updateState {
            val closingId = if (it.pageClosingSilentlyId == notificationId) null else it.pageClosingSilentlyId
            if (it.pageNotificationId != notificationId) {
                it.copy(pageClosingSilentlyId = closingId)
            } else {
                it.copy(
                    pageNotificationId = null,
                    isShowing = false,
                    isJoining = false,
                    isPageVisible = false,
                    pageClosingSilentlyId = closingId,
                    
                    conversationId = if (it.conversationId == conversationId &&
                        (it.currentNotificationId == null || it.currentNotificationId == notificationId)
                    ) null else it.conversationId,
                )
            }
        }
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

    fun updateState(block: (StateSnapshot) -> StateSnapshot) {
        _state.update { current ->
            val next = block(current)
            if (current == next) current else next
        }
    }

}