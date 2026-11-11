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











interface CallControlsOwner {
    
    fun toggleControls(): Boolean

    fun setControlsHidden(hidden: Boolean)
}

class CallUiController(session: CallSessionUiSource = CallSessionUiState()) {
    private var controlsOwner: CallControlsOwner? = null

    
    fun setControlsOwner(owner: CallControlsOwner?) {
        controlsOwner = owner
    }

    




    private fun revealForPanel(opening: Boolean) {
        if (opening && !_dockPinned.value) controlsOwner?.setControlsHidden(false)
    }

    





    fun applyLayoutControls(hidden: Boolean, docked: Boolean) {
        val dockChanged = _dockPinned.value != docked
        if (dockChanged) {
            _dockPinned.value = docked
            L.i { "[Call] dockPinned pinned=$docked" }
        }
        if (isShareScreening.value) {
            if (dockChanged) _showBottomToolBarViewEnabled.value = docked || _showTopStatusViewEnabled.value
            return
        }
        _showTopStatusViewEnabled.value = !hidden
        _showBottomToolBarViewEnabled.value = !hidden || docked
        if (hidden && !docked) closeParticipantsPanel(reason = "tapHide")
    }


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

    private val _pipContentAspect = MutableStateFlow<Float?>(null)

    
    val pipContentAspect: StateFlow<Float?> = _pipContentAspect.asStateFlow()

    private val _showTopStatusViewEnabled = MutableStateFlow(true)
    val showTopStatusViewEnabled = _showTopStatusViewEnabled.asStateFlow()

    private val _showBottomToolBarViewEnabled = MutableStateFlow(true)
    val showBottomToolBarViewEnabled = _showBottomToolBarViewEnabled.asStateFlow()

    



    private val _dockPinned = MutableStateFlow(false)
    val dockPinned = _dockPinned.asStateFlow()

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

    
    val isShareScreening: StateFlow<Boolean> = session.isShareScreening

    
    val countDownDurationStr: StateFlow<String> = session.countDownDurationStr

    private val _isCriticalAlertEnable = MutableStateFlow(false)
    val isCriticalAlertEnable = _isCriticalAlertEnable.asStateFlow()

    private val _isRequestingPermission = MutableStateFlow(false)
    val isRequestingPermission = _isRequestingPermission.asStateFlow()

    private val _showCriticalAlertConfirmViewEnabled = MutableStateFlow(false)
    val showCriticalAlertConfirmViewEnabled = _showCriticalAlertConfirmViewEnabled.asStateFlow()

    
    val speakingEnabled: StateFlow<Boolean> = session.speakingEnabled

    





    private val _participantMenuOpen = MutableStateFlow(false)
    val participantMenuOpen = _participantMenuOpen.asStateFlow()

    
    val reconnectCount: StateFlow<Int> = session.reconnectCount

    



    val networkQuality: StateFlow<NetworkQualityView> = session.networkQuality

    


    fun setPipModeEnabled(enabled: Boolean) {
        _isInPipMode.value = enabled
    }

    fun setPipContentAspect(aspect: Float?) {
        _pipContentAspect.value = aspect
    }

    


    fun setShowSimpleBarrageEnabled(enabled: Boolean) {
        revealForPanel(enabled)
        _showSimpleBarrageEnabled.value = enabled
    }

    


    fun setShowToolBarBottomViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowToolBarBottomViewEnable enabled:${enabled}" }
        revealForPanel(enabled)
        _showToolBarBottomViewEnable.value = enabled
    }

    


    fun setShowCriticalAlertConfirmViewEnabled(enabled: Boolean) {
        revealForPanel(enabled)
        _showCriticalAlertConfirmViewEnabled.value = enabled
    }

    


    fun setShowBottomCallEndViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowBottomCallEndViewEnable enabled:${enabled}" }
        revealForPanel(enabled)
        _showBottomCallEndViewEnable.value = enabled
    }

    fun setShowInviteViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowInviteViewEnable enabled:${enabled}" }
        revealForPanel(enabled)
        _showInviteViewEnable.value = enabled
    }

    


    fun setShowTopStatusViewEnabled(enabled: Boolean) {
        _showTopStatusViewEnabled.value = enabled
    }

    


    fun setShowBottomToolBarViewEnabled(enabled: Boolean) {
        _showBottomToolBarViewEnabled.value = enabled
    }

    




    fun toggleOverlays() {
        val bottomEnabled = _showBottomToolBarViewEnabled.value
        val owner = controlsOwner
        if (_showSimpleBarrageEnabled.value && bottomEnabled) {
            _showSimpleBarrageEnabled.value = false
        } else if (owner == null || isShareScreening.value || !owner.toggleControls()) {
            toggleOwnFlags(bottomEnabled)
        }
    }

    
    private fun toggleOwnFlags(bottomEnabled: Boolean) {
        _showTopStatusViewEnabled.value = !_showTopStatusViewEnabled.value
        if (_dockPinned.value) return
        _showBottomToolBarViewEnabled.value = !bottomEnabled
        if (bottomEnabled) closeParticipantsPanel(reason = "tapHide")
    }

    fun toggleTopBottomBars() {
        if (_dockPinned.value) {
            _showTopStatusViewEnabled.value = !_showTopStatusViewEnabled.value
            return
        }
        val bottomEnabled = _showBottomToolBarViewEnabled.value
        _showTopStatusViewEnabled.value = !_showTopStatusViewEnabled.value
        _showBottomToolBarViewEnabled.value = !bottomEnabled
        if (bottomEnabled) closeParticipantsPanel(reason = "tapHide")
    }

    



    fun autoHideChrome() {
        _showTopStatusViewEnabled.value = false
        if (_dockPinned.value) return
        _showBottomToolBarViewEnabled.value = false
        closeParticipantsPanel(reason = "autoHide")
    }

    



    private fun closeParticipantsPanel(reason: String) {
        if (!_showUsersEnabled.value) return
        _showUsersEnabled.value = false
        L.i { "[Call] participantsPanel closed reason=$reason" }
    }

    


    fun setShowUsersEnabled(enabled: Boolean) {
        revealForPanel(enabled)
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