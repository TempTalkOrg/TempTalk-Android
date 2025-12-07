package com.difft.android.call.core

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.BarrageMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CallUiController() {

    private val _showBottomCallEndViewEnable = MutableStateFlow(false)
    val showBottomCallEndViewEnable = _showBottomCallEndViewEnable.asStateFlow()

    private val _showToolBarBottomViewEnable = MutableStateFlow(false)
    val showToolBarBottomViewEnable = _showToolBarBottomViewEnable.asStateFlow()

    private val _showHandsUpEnabled = MutableStateFlow(false)
    val showHandsUpEnabled = _showHandsUpEnabled.asStateFlow()

    private val _showUsersEnabled = MutableStateFlow(false)
    val showUsersEnabled = _showUsersEnabled.asStateFlow()

    private val _showSimpleBarrageEnabled = MutableStateFlow(false)
    val showSimpleBarrageEnabled = _showSimpleBarrageEnabled.asStateFlow()

    private val _handsUpEnabled = MutableStateFlow(false)
    val handsUpEnabled = _handsUpEnabled.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode = _isInPipMode.asStateFlow()

    private val _showTopStatusViewEnabled = MutableStateFlow(true)
    val showTopStatusViewEnabled = _showTopStatusViewEnabled.asStateFlow()

    private val _showBottomToolBarViewEnabled = MutableStateFlow(true)
    val showBottomToolBarViewEnabled = _showBottomToolBarViewEnabled.asStateFlow()

    private val _barrageMessage = MutableStateFlow<BarrageMessage?>(null)
    val barrageMessage = _barrageMessage.asStateFlow()

    private val _isShareScreening = MutableStateFlow(false)
    val isShareScreening = _isShareScreening.asStateFlow()

    private val _countDownDurationStr = MutableStateFlow("00:00")
    val countDownDurationStr = _countDownDurationStr.asStateFlow()

    private val _isCriticalAlertEnable = MutableStateFlow(false)
    val isCriticalAlertEnable = _isCriticalAlertEnable.asStateFlow()

    private val _isRequestingPermission = MutableStateFlow(false)
    val isRequestingPermission = _isRequestingPermission.asStateFlow()

    /**
     * Enables or disables the "Hands Up" feature for participants in a call or meeting.
     */
    fun setHandsUpEnable(enabled: Boolean) {
        _handsUpEnabled.value = enabled
    }

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
     * Sets whether the "raise hand" view at the bottom of the screen is enabled for display.
     */
    fun setShowHandsUpBottomViewEnabled(enabled: Boolean) {
        L.i { "[Call] ShowHandsUpView setShowHandsUpBottomViewEnabled enabled:${enabled}" }
        _showHandsUpEnabled.value = enabled
    }

    /**
     * Sets whether the toolbar at the bottom of the view is enabled for display.
     */
    fun setShowToolBarBottomViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowToolBarBottomViewEnable enabled:${enabled}" }
        _showToolBarBottomViewEnable.value = enabled
    }

    /**
     * Sets whether the bottom call end view is enabled for display.
     */
    fun setShowBottomCallEndViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowBottomCallEndViewEnable enabled:${enabled}" }
        _showBottomCallEndViewEnable.value = enabled
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

    /**
     * Updates the state indicating whether user list display is enabled in the call UI.
     */
    fun setShowUsersEnabled(enabled: Boolean) {
        _showUsersEnabled.tryEmit(enabled)
    }

    /**
     * Updates the barrage message (floating chat message) displayed in the call UI.
     */
    fun setBarrageMessage(message: BarrageMessage?) {
        _barrageMessage.value = message
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

}