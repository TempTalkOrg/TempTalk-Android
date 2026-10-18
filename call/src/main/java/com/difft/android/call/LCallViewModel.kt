package com.difft.android.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.handler.CriticalAlertVisibility
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.call.permission.CallMediaPermissionCoordinator
import com.difft.android.call.session.CallFinishRequest
import com.difft.android.call.session.CallRoomState
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionEndReason
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.barrage.dispatchTo
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStatePort
import com.difft.android.network.UrlManager
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped facade over the call's [CallSession], which the page resolved and hands in. The
 * view model attaches to the session in its constructor and detaches in [onCleared]; only the
 * page's end path ([doExitClear]) ends the session. Everything else forwards to the session on
 * every read (never cached), so lazy / fail-loud / first-touch semantics are those of the session
 * itself.
 *
 * Owns the [CallUiController], bound to the session's UI state, and is the only collector of the
 * session's UI output: events and the group critical-alert flag are applied here, on main.
 */
@Suppress("TooManyFunctions") // one-line forwards consumed by the call UI
@HiltViewModel(assistedFactory = LCallViewModelFactory::class)
class LCallViewModel @AssistedInject constructor(
    application: Application,
    @Assisted private val session: CallSession,
    userManager: UserManager,
    private val urlManager: UrlManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
) : AndroidViewModel(application) {

    private val callRole = session.params.callRole

    val conversationId = session.params.callIntent.conversationId
    val mediaPermissions = CallMediaPermissionCoordinator(userManager)

    val callUiController = CallUiController(session.uiState)

    init {
        // UNDISPATCHED: both subscribe inside this constructor (on main). The first page to subscribe
        // receives, in order, the events the session emitted before any page existed.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.uiState.events.collect(::applySessionUiEvent)
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.uiState.groupCriticalAlert.collect(callUiController::setCriticalAlertEnable)
        }
        // Once per view model: a configuration change keeps it, so the page stays attached across one.
        session.attachUi()
        onGoingCallStateManager.callPageAttached()
        L.i { "[Call][SessionUi] attached session=${session.id}" }
    }

    val rtm: RtmMessageHandler get() = session.rtm
    val participantManager get() = session.participantManager
    val timerManager get() = session.timerManager
    val audioDeviceManager get() = session.audioDeviceManager

    val room get() = session.room
    val callStatus get() = session.callStatus
    val callType get() = session.callType

    val deNoiseEnable get() = session.deNoiseEnable
    val deNoiseMode get() = session.deNoiseMode
    val voicePreset get() = session.voicePreset

    fun setDeNoiseEnabled(enabled: Boolean) = session.setDeNoiseEnabled(enabled)
    fun setDeNoiseMode(mode: AudioModule) = session.setDeNoiseMode(mode)
    fun setVoicePreset(preset: VoicePreset) = session.setVoicePreset(preset)

    val isNoSpeakSoloTimeout get() = session.isNoSpeakSoloTimeout
    val micEnabled get() = session.micEnabled
    val cameraEnabled get() = session.cameraEnabled
    val mediaSendIssue: StateFlow<MediaSendIssueState> get() = session.mediaSendIssue
    val participants get() = participantManager.participants
    val screenSharingUser get() = participantManager.screenSharingUser
    val screenShareFloatingSpeaker: ScreenShareFloatingSpeakerStatePort get() = session.screenShareFloatingSpeaker
    val audioRoute get() = audioDeviceManager.routeSnapshot

    /** The session's room setup; the page shows the call once it is [CallRoomState.Ready]. */
    val roomState: StateFlow<CallRoomState> get() = session.roomState

    /** Whether the session has ended (main-confined read). */
    val isSessionEnded: Boolean get() = session.isEnded

    /** Releases the entry point's wait-dialog claim; idempotent. */
    fun releaseEntryWaitDialog() = session.releaseEntryWaitDialog()

    fun getE2eeKey(): ByteArray? = session.getE2eeKey()
    fun getRoomId(): String? = session.getRoomId()
    fun getOneOnOnePeerId(): String? = session.getOneOnOnePeerId()

    val e2eeLearnMoreUrl: String
        get() = urlManager.e2eeLearnMoreUrl

    fun getCallRoomName(): String = session.getCallRoomName()

    fun showCallBarrageMessage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) =
        session.showBarrage(participant, message, type)

    fun resetNoBodySpeakCheck() = session.resetNoBodySpeakCheck()
    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        session.setMicEnabled(enabled, publishMuted, isShowBarrage)
    fun handleConnectedState() = session.handleConnectedState()
    fun startCallDurationTimer() = session.startCallDurationTimer()
    fun applyInviteUpgrade() = session.applyInviteUpgrade()
    fun stopRingToneAndTimeoutCheck() = session.stopRingToneAndTimeoutCheck()

    /** The session's log-correlation id. */
    val sessionId: Long get() = session.id

    // --- end path (see CallEndController) ---
    fun markEnding(): Boolean = session.markEnding()
    val finishRequest: StateFlow<CallFinishRequest?> get() = session.finishRequest

    fun requestExit(params: CallExitParams, endType: CallEndType) = session.requestExit(params, endType)

    fun doExitClear() = session.end(CallSessionEndReason.EXIT_CLEAR)

    /** The page went away for good (not a configuration change): it detaches, the call goes on. */
    override fun onCleared() {
        super.onCleared()
        session.detachUi()
        onGoingCallStateManager.callPageDetached()
        L.i { "[Call][SessionUi] detached session=${session.id} ended=${session.isEnded}" }
    }

    fun updateScreenShareFallback(participant: Participant) = session.updateScreenShareFallback(participant)
    fun setCameraEnabled(enabled: Boolean) = session.setCameraEnabled(enabled)
    fun toggleMute(participant: Participant, displayName: String) = session.toggleMute(participant, displayName)
    fun flipCamera() = session.flipCamera()
    fun shouldTriggerFeedbackView() = session.shouldTriggerFeedbackView()
    fun isRequestingPermission() = callUiController.isRequestingPermission.value
    fun hasOtherActiveSpeaker(): Boolean = session.hasOtherActiveSpeaker()
    fun addAwaitingJoinInvitees(inviteeIds: List<String>) = session.addAwaitingJoinInvitees(inviteeIds)
    fun getCurrentCallUidList(): List<String> = session.getCurrentCallUidList()

    suspend fun handleCriticalAlertNew(gid: String? = null): Boolean = session.handleCriticalAlertNew(gid)

    fun is1v1ShowCriticalAlertEnable(callStatus: CallStatus): Boolean =
        CriticalAlertVisibility.for1v1(callType.value, callRole, callStatus)

    fun isGroupShowCriticalAlertEnable(isCriticalAlertEnable: Boolean): Boolean =
        CriticalAlertVisibility.forGroup(callType.value, isCriticalAlertEnable)

    fun isInstantCriticalAlertEnable(awaitingJoinInvitees: List<String>): Boolean =
        CriticalAlertVisibility.forInstant(callType.value, awaitingJoinInvitees)

    fun isControlButtonClickEnabled(): Boolean = session.isControlButtonClickEnabled()

    /** Total: never throws, so one bad event cannot stop barrages for the rest of the call. */
    private fun applySessionUiEvent(event: CallSessionUiEvent) = when (event) {
        is CallSessionUiEvent.Barrage -> event.renderable.dispatchTo(callUiController)
        is CallSessionUiEvent.Toast -> showToastSafely(event.message)
        CallSessionUiEvent.RevealCallChrome -> {
            callUiController.setShowTopStatusViewEnabled(true)
            callUiController.setShowBottomToolBarViewEnabled(true)
        }
    }

    private fun showToastSafely(message: String) {
        try {
            ToastUtil.show(message)
        } catch (e: Exception) {
            L.e(e) { "[Call] Failed to show toast message length=${message.length}" }
        }
    }
}
