package com.difft.android.call.core

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.TextBubbleMessage
import com.difft.android.call.network.NetworkQualityView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class CallUiController() {

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

    private val _isShareScreening = MutableStateFlow(false)
    val isShareScreening = _isShareScreening.asStateFlow()

    private val _countDownDurationStr = MutableStateFlow("00:00")
    val countDownDurationStr = _countDownDurationStr.asStateFlow()

    private val _isCriticalAlertEnable = MutableStateFlow(false)
    val isCriticalAlertEnable = _isCriticalAlertEnable.asStateFlow()

    private val _isRequestingPermission = MutableStateFlow(false)
    val isRequestingPermission = _isRequestingPermission.asStateFlow()

    private val _showCriticalAlertConfirmViewEnabled = MutableStateFlow(false)
    val showCriticalAlertConfirmViewEnabled = _showCriticalAlertConfirmViewEnabled.asStateFlow()

    private val _speakingEnabled = MutableStateFlow(true)
    val speakingEnabled = _speakingEnabled.asStateFlow()

    /**
     * A participant mute menu (grid tile or screen-share panel) is showing. It lives in its own
     * Popup window, so the root pointer listener never sees the user reading it; the screen-share
     * auto-hide timer treats it as ongoing interaction. Deliberately not part of the panel set
     * that fades the chrome out — a one-line menu is not a sheet.
     */
    private val _participantMenuOpen = MutableStateFlow(false)
    val participantMenuOpen = _participantMenuOpen.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount = _reconnectCount.asStateFlow()

    /**
     * Room-wide weak-network verdict. Both suppression rules are already applied by
     * [com.difft.android.call.network.NetworkQualityTracker], so the UI must never re-implement
     * them — and must never branch on [NetworkQualityView.suppressed], because the snapshot is
     * already empty when that flag is true.
     *
     * Written only by NetworkQualityCoordinator, on the main dispatcher.
     */
    private val _networkQuality = MutableStateFlow(NetworkQualityView.NONE)
    val networkQuality = _networkQuality.asStateFlow()

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
     * Updates the screen-sharing state in the call UI.
     */
    fun setShareScreening(enabled: Boolean) {
        _isShareScreening.value = enabled
    }

    /**
     * Sets the formatted string representing the remaining countdown duration for display in the UI.
     */
    fun setCountDownDurationStr(str: String) {
        _countDownDurationStr.value = str
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

    fun setSpeakingEnabled(enabled: Boolean) {
        _speakingEnabled.value = enabled
    }

    fun setParticipantMenuOpen(open: Boolean) {
        _participantMenuOpen.value = open
    }

    fun incrementReconnectCount() {
        _reconnectCount.value++
    }

    /**
     * Publishes a whole new weak-network snapshot. A full replacement, not a read-modify-write, so
     * plain assignment is correct here; `MutableStateFlow` drops equal values, which is the
     * de-duplication for the 500 ms tick.
     *
     * Public so the render-layer Compose tests can drive the UI with a bare `CallUiController()`.
     */
    fun setNetworkQuality(view: NetworkQualityView) {
        _networkQuality.value = view
    }

    @Volatile
    var screenShareLastInteractionTime: Long = System.currentTimeMillis()
        private set

    fun notifyScreenShareInteraction() {
        screenShareLastInteractionTime = System.currentTimeMillis()
    }

}