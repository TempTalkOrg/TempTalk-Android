package com.difft.android.call

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.core.CallTlsProvider
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.feedback.CallFeedbackBinder
import com.difft.android.call.handler.CriticalAlertVisibility
import com.difft.android.call.ui.barrage.CallBarrageFormatter
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallFeedbackManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.cleanup.CallCleanupSteps
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.handler.RoomEventHost
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.media.CallAudioSetup
import com.difft.android.call.media.CallMediaController
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.session.CallObservers
import com.difft.android.call.session.CallSessionStarter
import com.difft.android.call.session.InstantCallConverter
import com.difft.android.call.session.createRtmHandler
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.screenshare.ScreenShareFallbackSource
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStatePort
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import livekit.org.webrtc.CameraXHelper
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor

@HiltViewModel(assistedFactory = LCallViewModelFactory::class)
class LCallViewModel @AssistedInject constructor(
    application: Application,
    @Assisted private val e2eeEnable: Boolean,
    @Assisted private val callIntent: CallIntent,
    @Assisted private val callConfig: CallConfig,
    @Assisted private val callRole: CallRole,
    /**
     * Global voice-changer preference applied when the call starts. Per product spec,
     * mid-call changes via [setVoicePreset] do NOT write back to this preference —
     * the next call restores the user's saved value.
     */
    @Assisted private val initialVoicePreset: VoicePreset,
    private val callToChatController: LCallToChatController,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager,
    private val callVibrationManager: CallVibrationManager,
    private val callRingtoneManager: CallRingtoneManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callFeedbackManager: CallFeedbackManager,
    private val callStatisticsLogManager: CallStatisticsLogManager,
    private val callTimeoutManager: CallTimeoutManager,
    private val callTlsProvider: CallTlsProvider,
    @ChativeHttpClientModule.Chat private val httpClient: dagger.Lazy<ChativeHttpClient>,
    private val userManager: UserManager,
    private val proxyConfigProvider: com.difft.android.network.proxy.ProxyConfigProvider,
) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private val mySelfId: String by lazy { globalServices.myId }
    val conversationId = callIntent.conversationId
    private var roomId: String? = null
    private var e2eeKey: ByteArray? = null
    private val audioProcessor = AudioPipelineProcessor(application)
    lateinit var rtm: RtmMessageHandler
    val participantManager = ParticipantManager(viewModelScope)
    val callUiController = CallUiController()
    val timerManager = TimerManager(viewModelScope)
    val audioDeviceManager = AudioDeviceManager(application, callIntent.callType, userManager)
    val audioHandler get() = audioDeviceManager.audioHandler
    private val audioSetup = CallAudioSetup(
        scope = viewModelScope,
        audioDeviceManager = audioDeviceManager,
        audioProcessor = audioProcessor,
        callConfig = callConfig,
        isDenoiseEnabledProvider = { deNoiseEnable.value },
        userManager = userManager,
    )

    private val roomCtl = CallRoomController(
        appContext = application,
        callIntent = callIntent,
        audioHandler = audioHandler,
        audioProcessor = audioProcessor,
        e2eeEnable = e2eeEnable,
        proxyConfigProvider = proxyConfigProvider,
        decryptCallMKey = { eKey, eMKey -> messageEncryptor.decryptCallKey(eKey, eMKey) }
    )

    private lateinit var activeSpeakers: StateFlow<List<Participant>>

    val room get() = roomCtl.room
    val callStatus get() = roomCtl.callStatus
    val callType get() = roomCtl.callType
    val error get() = roomCtl.error

    private lateinit var mediaCtl: CallMediaController

    val deNoiseEnable get() = mediaCtl.deNoiseEnable
    val deNoiseMode get() = mediaCtl.deNoiseMode
    val voicePreset get() = mediaCtl.voicePreset

    fun setDeNoiseEnabled(enabled: Boolean) = mediaCtl.setDeNoiseEnabled(enabled)

    fun setDeNoiseMode(mode: AudioModule) = mediaCtl.setDeNoiseMode(mode)

    fun setVoicePreset(preset: VoicePreset) = mediaCtl.setVoicePreset(preset)

    /**
     * Apply the user's global voice-changer preference at call start.
     * Safe to call for ORIGINAL too — it resets any lingering SoundTouch state
     * on the shared [audioProcessor].
     */
    private fun applyInitialVoicePreset() {
        L.i { "[call] LCallViewModel applyInitialVoicePreset preset=${initialVoicePreset.sdkKey}" }
        audioDeviceManager.switchVoicePreset(initialVoicePreset)
        mediaCtl.setVoicePreset(initialVoicePreset)
    }

    val isNoSpeakSoloTimeout get() = roomCtl.isNoSpeakSoloTimeout
    val micEnabled get() = roomCtl.micEnabled
    val cameraEnabled get() = roomCtl.cameraEnabled
    val participants get() = participantManager.participants
    val screenSharingUser get() = participantManager.screenSharingUser
    val screenShareFloatingSpeaker: ScreenShareFloatingSpeakerStatePort
        get() = screenShareFloatingSpeakerStateHolder
    val currentAudioDevice get() = audioDeviceManager.selected

    private val feedbackBinder = CallFeedbackBinder(
        timerManager = timerManager,
        callFeedbackManager = callFeedbackManager,
        roomIdGetter = { roomId },
    )

    private val connectionCoordinator = CallConnectionCoordinator(
        appContext = application,
        roomCtl = roomCtl,
        callTlsProvider = callTlsProvider,
        statisticsLogManager = callStatisticsLogManager,
    )

    private val screenSharePreWarmer = ScreenSharePreWarmer(viewModelScope)

    private val timeoutMonitor = CallTimeoutMonitor(
        appContext = application,
        roomCtl = roomCtl,
        callTimeoutManager = callTimeoutManager,
        onGoingCallStateManager = onGoingCallStateManager,
        callRole = callRole,
        roomIdGetter = { roomId },
    )

    private val criticalAlertDispatcher by lazy {
        CriticalAlertDispatcher(
            scope = viewModelScope,
            httpClientProvider = { httpClient.get() },
            callToChatController = callToChatController,
            participantManager = participantManager,
            callUiController = callUiController,
            room = room,
            roomIdGetter = { roomId },
            showBarrage = { p, m -> showCallBarrageMessage(p, m) },
            showToast = ::showToastMessage,
        )
    }

    private val speakerState = SpeakerStateHolder(
        scope = viewModelScope,
        roomCtl = roomCtl,
        participantManager = participantManager,
        onGoingCallStateManager = onGoingCallStateManager,
    )

    private val screenShareFallback by lazy {
        ScreenShareFallbackSource(viewModelScope, room.localParticipant)
    }

    private val _screenShareSpeakerLazy = lazy {
        ScreenShareFloatingSpeakerStateHolder(
            scope = viewModelScope,
            participantManager = participantManager,
            speakingEnabled = callUiController.speakingEnabled,
            context = ApplicationHelper.instance,
            contactorCacheManager = contactorCacheManager,
            explicitFallback = screenShareFallback.flow,
        )
    }
    private val screenShareFloatingSpeakerStateHolder by _screenShareSpeakerLazy

    private val cleanupExecutor = CallCleanupExecutor()

    private val roomEventHost by lazy {
        RoomEventHost(
            showBarrageFn = { p, m, t -> showCallBarrageMessage(p, m, t) },
            setMicEnabledFn = { e, pm, sb -> setMicEnabled(e, pm, sb) },
            setCameraEnabledFn = { e -> setCameraEnabled(e) },
            resetNoBodySpeakCheckFn = ::resetNoBodySpeakCheck,
            sendHangUpBroadcastFn = ::sendHangUpBroadcast,
            stopRingToneAndTimeoutCheckFn = ::stopRingToneAndTimeoutCheck,
            switchToInstantCallFn = ::switchToInstantCall,
            handleConnectedStateFn = ::handleConnectedState,
            onFeedbackIdentityResolvedFn = feedbackBinder::onIdentityResolved,
            onNetworkPoorStateChangedFn = { feedbackBinder.currentCallNetworkPoor = it },
            getCurrentCallTypeFn = ::getCurrentCallType,
            getCurrentRoomIdFn = { roomId },
        )
    }

    private val _roomEventDispatcherLazy = lazy {
        RoomEventDispatcher(
            scope = viewModelScope,
            room = room,
            roomCtl = roomCtl,
            rtm = rtm,
            connectionCoordinator = connectionCoordinator,
            callUiController = callUiController,
            participantManager = participantManager,
            screenSharePreWarmer = screenSharePreWarmer,
            timeoutMonitor = timeoutMonitor,
            timerManager = timerManager,
            speakerState = speakerState,
            callDataManager = callDataManager,
            statisticsLogManager = callStatisticsLogManager,
            json = json,
            mySelfId = mySelfId,
            host = roomEventHost,
        )
    }
    private val roomEventDispatcher by _roomEventDispatcherLazy

    private val instantCallConverter by lazy {
        InstantCallConverter(
            scope = viewModelScope,
            callDataManager = callDataManager,
            contactorCacheManager = contactorCacheManager,
            roomCtl = roomCtl,
            callIntent = callIntent,
            callRole = callRole,
            mySelfId = mySelfId,
        )
    }

    private val sessionStarter by lazy {
        CallSessionStarter(
            scope = viewModelScope,
            room = room,
            roomCtl = roomCtl,
            connectionCoordinator = connectionCoordinator,
            onGoingCallStateManager = onGoingCallStateManager,
            callRingtoneManager = callRingtoneManager,
            callDataManager = callDataManager,
            contactorCacheManager = contactorCacheManager,
            callToChatController = callToChatController,
            messageEncryptor = messageEncryptor,
            callIntent = callIntent,
            e2eeEnable = e2eeEnable,
            mySelfId = mySelfId,
            createCallMsgConfig = callConfig.createCallMsg,
            onRoomIdAssigned = { rid ->
                roomId = rid
                callStatisticsLogManager.setRoomId(rid)
            },
            onE2eeKeyAssigned = { key -> e2eeKey = key },
        )
    }
    private val roomWiringStarted = AtomicBoolean(false)
    init {
        initCameraProvider(application)
        audioSetup.start()
        contactorCacheManager.startParticipantObservation(viewModelScope)
    }

    /** Phase B: create [room] off-main (LiveKit.create) then wire all room-dependent collaborators; call once. */
    fun startRoomDependentWiring() {
        if (!roomWiringStarted.compareAndSet(false, true)) return
        val r = roomCtl.createRoom()
        activeSpeakers = r::activeSpeakers.flow
        mediaCtl = CallMediaController(
            room = r,
            roomCtl = roomCtl,
            audioProcessor = audioProcessor,
            audioDeviceManager = audioDeviceManager,
            scope = viewModelScope,
            showBarrage = { p, m -> showCallBarrageMessage(p, m) },
            showToast = { m -> showToastMessage(m) },
        )
        initRtmHandler()
        if (roomCtl.isReleaseIntended()) { L.i { "[Call] Phase B abort: release in flight" }; return }
        applyInitialVoicePreset()
        roomEventDispatcher.startCollectingRoomEvents()
        roomEventDispatcher.startCollectingParticipants()
        CallObservers.register(
            scope = viewModelScope,
            room = r,
            participantManager = participantManager,
            callUiController = callUiController,
            contactorCacheManager = contactorCacheManager,
            callToChatController = callToChatController,
            speakerState = speakerState,
            callConfig = callConfig,
            callIntent = callIntent,
            activeSpeakers = activeSpeakers,
        )
        connectionCoordinator.observeManualSwitchReconnect(
            scope = viewModelScope,
            callIntent = callIntent,
            roomIdGetter = { roomId },
            showToast = { m -> showToastMessage(m) },
        )
        if (roomCtl.isReleaseIntended()) { L.i { "[Call] Phase B abort: release in flight (pre-connect re-check)" }; return }
        checkCriticalAlertStatusById(callIntent) // forces criticalAlertDispatcher → room getter; keep under the guard
        sessionStarter.start()
    }

    fun getE2eeKey(): ByteArray? = e2eeKey

    fun getRoomId(): String? = roomId

    fun getCallRoomName(): String {
        val name = instantCallConverter.currentRoomName
        // Read the participants StateFlow (already includes the local participant, so its
        // size equals room.remoteParticipants.size + 1) instead of touching the fail-loud
        // room getter. This Composable is invoked imperatively during recomposition and can
        // race with call teardown (releaseLocked sets released=true before callStatus flips
        // to DISCONNECTED); reading room there crashes with "room accessed after release".
        val participantNum = participants.value.size.coerceAtLeast(1)
        return if (getCurrentCallType() == CallType.ONE_ON_ONE.type) name else "$name ($participantNum)"
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun initCameraProvider(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let {
                if (it.isSupported(context)) {
                    CameraCapturerUtils.registerCameraProvider(it)
                    cameraProvider = it
                }
            }
        }
    }

    private fun initRtmHandler() {
        rtm = createRtmHandler(room, viewModelScope, callToChatController, messageEncryptor) { e2eeKey }
    }

    fun showCallBarrageMessage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) {
        viewModelScope.launch(Dispatchers.IO) {
            CallBarrageFormatter.formatAndDispatch(
                participant.identity?.value, message, type,
                callConfig, contactorCacheManager, callUiController,
            )
        }
    }

    fun resetNoBodySpeakCheck() {
        // Read the participants StateFlow (local + remotes) instead of the fail-loud room
        // getter: this is invoked from the end-reminder dialog's "Continue" path and from
        // 1v1 room events, both of which can race with call teardown (released=true).
        val snapshot = participants.value
        val hasRemote = snapshot.any { it is RemoteParticipant }
        val isSilent = snapshot.firstOrNull { it.isMicrophoneEnabled } == null || activeSpeakers.value.isEmpty()
        speakerState.reset(hasRemote, isSilent, callConfig)
    }

    private fun sendHangUpBroadcast(roomId: String) = ApplicationHelper.instance.sendBroadcast(
        Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
            putExtra(LCallActivity.EXTRA_CONTROL_TYPE, CallActionType.HANGUP.type)
            putExtra(LCallActivity.EXTRA_PARAM_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
    )

    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        mediaCtl.setMicEnabled(enabled, publishMuted, isShowBarrage)

    fun handleConnectedState() {
        roomCtl.updateCallStatus(CallStatus.CONNECTED)
        timerManager.startCallTimer { show -> roomId?.let { onGoingCallStateManager.updateCallingTime(it, show) } }
    }

    fun stopRingToneAndTimeoutCheck() {
        callRingtoneManager.stopRingTone()
        callVibrationManager.stopVibration()
        timeoutMonitor.cancelIfActive()
    }

    fun switchToInstantCall() = instantCallConverter.switchToInstantCall(roomId)
    fun doExitClear() = cleanupExecutor.start(reason = "doExitClear", steps = buildCleanupSteps())

    override fun onCleared() {
        super.onCleared()
        L.i { "[Call] LCallViewModel onCleared start." }
        screenSharePreWarmer.cleanupAll()
        cleanupExecutor.start(reason = "onCleared", steps = buildCleanupSteps())
        L.i { "[Call] LCallViewModel onCleared done." }
    }

    private fun buildCleanupSteps(): List<CallCleanupExecutor.Step> = CallCleanupSteps.build(
        application = application,
        audioProcessor = audioProcessor,
        audioHandler = audioHandler,
        audioSetup = audioSetup,
        timerManager = timerManager,
        timeoutMonitor = timeoutMonitor,
        roomCtl = roomCtl,
        speakerState = speakerState,
        screenShareFloatingSpeakerStateHolder = _screenShareSpeakerLazy.takeIf { it.isInitialized() }?.value,
        screenSharePreWarmer = screenSharePreWarmer,
        roomEventDispatcher = _roomEventDispatcherLazy.takeIf { it.isInitialized() }?.value,
        statisticsLogManager = callStatisticsLogManager,
        feedbackBinder = feedbackBinder,
        shouldTriggerFeedbackView = { shouldTriggerFeedbackView() },
        clearE2eeKey = {
            e2eeKey?.fill(0)
            e2eeKey = null
        },
    )

    fun updateScreenShareFallback(participant: Participant) = screenShareFallback.update(participant)
    fun setCameraEnabled(enabled: Boolean) = mediaCtl.setCameraEnabled(enabled)
    fun toggleMute(participant: Participant) = rtm.toggleMute(participant)
    fun flipCamera() = mediaCtl.flipCamera()
    fun shouldTriggerFeedbackView() = feedbackBinder.maybeTrigger()
    fun isRequestingPermission() = callUiController.isRequestingPermission.value
    fun hasOtherActiveSpeaker(): Boolean = speakerState.hasOtherActiveSpeaker()
    fun addAwaitingJoinInvitees(inviteeIds: List<String>) = participantManager.addAwaitingJoinInvitees(inviteeIds)

    // Uses the participants StateFlow rather than the fail-loud room getter: this is invoked
    // from CallExitHandler's async hangup callback, which can run after a concurrent path has
    // already released the room (remote onLeave / onCleared / cleanup). The snapshot also
    // retains the last-known remotes even after room.remoteParticipants is cleared by disconnect.
    fun getCurrentCallUidList(): List<String> = participants.value
        .filterIsInstance<RemoteParticipant>()
        .mapNotNull { it.identity?.value }
        .map { userId -> if (userId.contains(".")) userId.split(".")[0] else userId }

    suspend fun handleCriticalAlertNew(gid: String? = null): Boolean = criticalAlertDispatcher.send(gid)

    fun is1v1ShowCriticalAlertEnable(callStatus: CallStatus): Boolean =
        CriticalAlertVisibility.for1v1(callType.value, callRole, callStatus)

    fun isGroupShowCriticalAlertEnable(isCriticalAlertEnable: Boolean): Boolean =
        CriticalAlertVisibility.forGroup(callType.value, isCriticalAlertEnable)

    fun isInstantCriticalAlertEnable(awaitingJoinInvitees: List<String>): Boolean =
        CriticalAlertVisibility.forInstant(callType.value, awaitingJoinInvitees)

    // Uses the non-throwing roomStateOrNull() rather than the fail-loud room getter: this runs from a
    // touch handler that can legitimately race with call teardown (cleanup/onLeave releasing the room).
    // A released/not-yet-created room reads null here → button treated as not clickable, instead of crashing.
    fun isControlButtonClickEnabled(): Boolean = if (callType.value == CallType.ONE_ON_ONE.type)
        roomCtl.roomStateOrNull() == Room.State.CONNECTED
    else
        callStatus.value == CallStatus.CONNECTED || callStatus.value == CallStatus.RECONNECTED

    private fun getCurrentCallType(): String = callDataManager.getCallData(roomId)?.type ?: ""
    private fun checkCriticalAlertStatusById(callIntent: CallIntent) = criticalAlertDispatcher.refreshGroupStatus(callIntent)

    private fun showToastMessage(message: String) {
        viewModelScope.launch {
            try { ToastUtil.show(message) } catch (e: Exception) { L.e(e) { "[Call] Failed to show toast message: $message" } }
        }
    }
}
