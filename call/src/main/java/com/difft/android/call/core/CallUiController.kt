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
/**
 * Owns whether the controls are hidden outside a share (design §4.4): the layout controller, whose
 * state the page mirrors back through [CallUiController.applyLayoutControls].
 */
interface CallControlsOwner {
    /** Toggles the controls; false when the owner cannot yet (nothing laid out), so the flags do. */
    fun toggleControls(): Boolean

    fun setControlsHidden(hidden: Boolean)
}

class CallUiController(session: CallSessionUiSource = CallSessionUiState()) {
    private var controlsOwner: CallControlsOwner? = null

    /** Hands tap-to-hide outside a share to [owner]; until set, the flags toggle themselves. */
    fun setControlsOwner(owner: CallControlsOwner?) {
        controlsOwner = owner
    }

    /**
     * A panel or sheet is opening: the controls come back first (design §4.4), except while the
     * tabletop dock is pinned, where the console that opened it is always shown and the enlarged
     * stage above the fold stays.
     */
    private fun revealForPanel(opening: Boolean) {
        if (opening && !_dockPinned.value) controlsOwner?.setControlsHidden(false)
    }

    /**
     * The layout controller's controls state: the title bar follows [hidden]; the toolbar too,
     * except in the tabletop posture ([docked]), where it stays at the window bottom. Hiding the
     * toolbar closes the participants panel, as a tap always did. A share keeps its own flags; a
     * posture change during it only puts the toolbar back in step with the title (shown while docked).
     */
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

    /** Width / height of the video the PiP window should match; null keeps the default shape. */
    val pipContentAspect: StateFlow<Float?> = _pipContentAspect.asStateFlow()

    private val _showTopStatusViewEnabled = MutableStateFlow(true)
    val showTopStatusViewEnabled = _showTopStatusViewEnabled.asStateFlow()

    private val _showBottomToolBarViewEnabled = MutableStateFlow(true)
    val showBottomToolBarViewEnabled = _showBottomToolBarViewEnabled.asStateFlow()

    /**
     * The tabletop dock is shown: the controls stay on screen below the fold, so taps and the
     * screen-share auto-hide toggle only the title bar and leave the participants panel open.
     */
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

    fun setPipContentAspect(aspect: Float?) {
        _pipContentAspect.value = aspect
    }

    /**
     * Controls the visibility of simplified barrage (floating chat) messages in the call UI.
     */
    fun setShowSimpleBarrageEnabled(enabled: Boolean) {
        revealForPanel(enabled)
        _showSimpleBarrageEnabled.value = enabled
    }

    /**
     * Sets whether the toolbar at the bottom of the view is enabled for display.
     */
    fun setShowToolBarBottomViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowToolBarBottomViewEnable enabled:${enabled}" }
        revealForPanel(enabled)
        _showToolBarBottomViewEnable.value = enabled
    }

    /**
     * Sets whether the critical alert confirm view at the bottom of the view is enabled for display.
     */
    fun setShowCriticalAlertConfirmViewEnabled(enabled: Boolean) {
        revealForPanel(enabled)
        _showCriticalAlertConfirmViewEnabled.value = enabled
    }

    /**
     * Sets whether the bottom call end view is enabled for display.
     */
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
     * A tap on the call (root, tile or the hidden title): first closes an open barrage; outside a
     * share hands the toggle to the controls owner, whose state comes back through
     * [applyLayoutControls]; a share toggles its own bars.
     */
    fun toggleOverlays() {
        val bottomEnabled = _showBottomToolBarViewEnabled.value
        val owner = controlsOwner
        if (_showSimpleBarrageEnabled.value && bottomEnabled) {
            _showSimpleBarrageEnabled.value = false
        } else if (owner == null || isShareScreening.value || !owner.toggleControls()) {
            toggleOwnFlags(bottomEnabled)
        }
    }

    /** The bars' own toggle: a share's, or before the controls owner has laid anything out. */
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

    /**
     * The screen-share idle auto-hide: both bars, and the participants panel as a tap-to-hide does;
     * only the title bar while the tabletop dock is pinned.
     */
    fun autoHideChrome() {
        _showTopStatusViewEnabled.value = false
        if (_dockPinned.value) return
        _showBottomToolBarViewEnabled.value = false
        closeParticipantsPanel(reason = "autoHide")
    }

    /**
     * Hiding the chrome by tap or by the auto-hide also closes the participants panel so it never
     * floats on its own (#1451). Other writers of the bottom flag (the group end menu) leave the panel open.
     */
    private fun closeParticipantsPanel(reason: String) {
        if (!_showUsersEnabled.value) return
        _showUsersEnabled.value = false
        L.i { "[Call] participantsPanel closed reason=$reason" }
    }

    /**
     * Updates the state indicating whether user list display is enabled in the call UI.
     */
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