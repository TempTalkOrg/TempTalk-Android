package com.difft.android.call.core

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.TextBubbleMessage
import com.difft.android.call.network.NetworkQualityView
import com.difft.android.call.session.CallSessionUiSource
import com.difft.android.call.session.CallSessionUiState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Call UI state. Owned by `LCallViewModel`; the UI writes its own fields here. The five
 * session-written fields ([isShareScreening], [countDownDurationStr], [speakingEnabled],
 * [reconnectCount], [networkQuality]) are the session's own `StateFlow` objects, so their values,
 * write order and conflation are the session's, and they have no setter here.
 */
class CallUiController(session: CallSessionUiSource = CallSessionUiState()) {

    private val _showBottomCallEndViewEnable = MutableStateFlow(false)
    val showBottomCallEndViewEnable = _showBottomCallEndViewEnable.asStateFlow()

    private val _showInviteViewEnable = MutableStateFlow(false)
    val showInviteViewEnable = _showInviteViewEnable.asStateFlow()

    private val _showToolBarBottomViewEnable = MutableStateFlow(false)
    val showToolBarBottomViewEnable = _showToolBarBottomViewEnable.asStateFlow()

    private val _showUsersEnabled = MutableStateFlow(false)
    val showUsersEnabled = _showUsersEnabled.asStateFlow()

    private val _showSimpleBarrageEnabled = MutableStateFlow(false)
    val showSimpleBarrageEnabled = _showSimpleBarrageEnabled.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode = _isInPipMode.asStateFlow()

    private val _showTopStatusViewEnabled = MutableStateFlow(true)
    val showTopStatusViewEnabled = _showTopStatusViewEnabled.asStateFlow()

    private val _showBottomToolBarViewEnabled = MutableStateFlow(true)
    val showBottomToolBarViewEnabled = _showBottomToolBarViewEnabled.asStateFlow()

    private val _barrageMessage = MutableSharedFlow<BarrageMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val barrageMessage: SharedFlow<BarrageMessage> = _barrageMessage.asSharedFlow()

    private val _emojiBubbleMessage = MutableSharedFlow<EmojiBubbleMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val emojiBubbleMessage: SharedFlow<EmojiBubbleMessage> = _emojiBubbleMessage.asSharedFlow()

    private val _textBubbleMessage = MutableSharedFlow<TextBubbleMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textBubbleMessage: SharedFlow<TextBubbleMessage> = _textBubbleMessage.asSharedFlow()

    /** Bound to the session (same object). */
    val isShareScreening: StateFlow<Boolean> = session.isShareScreening

    /** Bound to the session (same object). */
    val countDownDurationStr: StateFlow<String> = session.countDownDurationStr

    private val _isCriticalAlertEnable = MutableStateFlow(false)
    val isCriticalAlertEnable = _isCriticalAlertEnable.asStateFlow()

    private val _isRequestingPermission = MutableStateFlow(false)
    val isRequestingPermission = _isRequestingPermission.asStateFlow()

    private val _showCriticalAlertConfirmViewEnabled = MutableStateFlow(false)
    val showCriticalAlertConfirmViewEnabled = _showCriticalAlertConfirmViewEnabled.asStateFlow()

    /** Bound to the session (same object). */
    val speakingEnabled: StateFlow<Boolean> = session.speakingEnabled

    /**
     * A participant mute menu (grid tile or screen-share panel) is showing. It lives in its own
     * Popup window, so the root pointer listener never sees the user reading it; the screen-share
     * auto-hide timer treats it as ongoing interaction. Deliberately not part of the panel set
     * that fades the chrome out — a one-line menu is not a sheet.
     */
    private val _participantMenuOpen = MutableStateFlow(false)
    val participantMenuOpen = _participantMenuOpen.asStateFlow()

    /** Bound to the session (same object). */
    val reconnectCount: StateFlow<Int> = session.reconnectCount

    /**
     * Room-wide weak-network verdict, bound to the session (same object). See
     * [CallSessionUiSource.networkQuality] for the suppression contract the UI relies on.
     */
    val networkQuality: StateFlow<NetworkQualityView> = session.networkQuality

    /**
     * Updates the Picture-in-Picture (PiP) mode state for the current call.
     */
    fun setPipModeEnabled(enabled: Boolean) {
        _isInPipMode.value = enabled
    }

    /**
     * Controls the visibility of simplified barrage (floating chat) messages in the call UI.
     */
    fun setShowSimpleBarrageEnabled(enabled: Boolean) {
        _showSimpleBarrageEnabled.value = enabled
    }

    /**
     * Sets whether the toolbar at the bottom of the view is enabled for display.
     */
    fun setShowToolBarBottomViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowToolBarBottomViewEnable enabled:${enabled}" }
        _showToolBarBottomViewEnable.value = enabled
    }

    /**
     * Sets whether the critical alert confirm view at the bottom of the view is enabled for display.
     */
    fun setShowCriticalAlertConfirmViewEnabled(enabled: Boolean) {
        _showCriticalAlertConfirmViewEnabled.value = enabled
    }

    /**
     * Sets whether the bottom call end view is enabled for display.
     */
    fun setShowBottomCallEndViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowBottomCallEndViewEnable enabled:${enabled}" }
        _showBottomCallEndViewEnable.value = enabled
    }

    fun setShowInviteViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowInviteViewEnable enabled:${enabled}" }
        _showInviteViewEnable.value = enabled
    }

    /**
     * Sets whether the top status view should be visible or hidden.
     */
    fun setShowTopStatusViewEnabled(enabled: Boolean) {
        _showTopStatusViewEnabled.value = enabled
    }

    /**
     * Sets whether the bottom toolbar view should be visible or hidden.
     */
    fun setShowBottomToolBarViewEnabled(enabled: Boolean) {
        _showBottomToolBarViewEnabled.value = enabled
    }

    fun toggleOverlays() {
        val bottomEnabled = _showBottomToolBarViewEnabled.value
        if (_showSimpleBarrageEnabled.value && bottomEnabled) {
            _showSimpleBarrageEnabled.value = false
        } else {
            _showTopStatusViewEnabled.value = !_showTopStatusViewEnabled.value
            _showBottomToolBarViewEnabled.value = !bottomEnabled
        }
    }

    fun toggleTopBottomBars() {
        _showTopStatusViewEnabled.value = !_showTopStatusViewEnabled.value
        _showBottomToolBarViewEnabled.value = !_showBottomToolBarViewEnabled.value
    }

    /**
     * Updates the state indicating whether user list display is enabled in the call UI.
     */
    fun setShowUsersEnabled(enabled: Boolean) {
        _showUsersEnabled.tryEmit(enabled)
    }

    fun setBarrageMessage(message: BarrageMessage) {
        _barrageMessage.tryEmit(message)
    }

    fun setEmojiBubbleMessage(message: EmojiBubbleMessage) {
        _emojiBubbleMessage.tryEmit(message)
    }

    fun setTextBubbleMessage(message: TextBubbleMessage) {
        _textBubbleMessage.tryEmit(message)
    }

    /**
     * Sets the critical alert enable state and updates the LiveData value.
     */
    fun setCriticalAlertEnable(enable: Boolean) {
        _isCriticalAlertEnable.value = enable
    }

    fun setRequestPermissionStatus(status: Boolean) {
        _isRequestingPermission.value = status
    }

    fun setParticipantMenuOpen(open: Boolean) {
        _participantMenuOpen.value = open
    }

    @Volatile
    var screenShareLastInteractionTime: Long = System.currentTimeMillis()
        private set

    fun notifyScreenShareInteraction() {
        screenShareLastInteractionTime = System.currentTimeMillis()
    }

}