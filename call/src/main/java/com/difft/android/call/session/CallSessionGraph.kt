package com.difft.android.call.session

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallEngine
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.feedback.CallFeedbackBinder
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.handler.CallExitSession
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.handler.RoomEventHost
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.manager.shouldHoldProximityLock
import com.difft.android.call.media.CallMediaController
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.state.OngoingCallPublisher
import com.difft.android.call.ui.barrage.CallBarrageFormatter
import com.difft.android.call.ui.screenshare.ScreenShareFallbackSource
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean




@Suppress("TooManyFunctions") 
internal class CallSessionGraph(
    val params: CallSessionParams,
    val deps: CallSessionDeps,
    val components: CallSessionComponentsFactory,
    val mainScope: CoroutineScope,
    
    val sessionId: Long,
    
    private val endSessionWithoutUi: () -> Unit,
) {
    
    val uiState = CallSessionUiState()

    
    val publisher = OngoingCallPublisher(sessionId, deps.onGoingCallStateManager)

    
    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val mySelfId: String by lazy { components.selfId() }

    
    @Volatile var roomId: String? = null
        private set

    @Volatile var e2eeKey: ByteArray? = null
        private set

    val audio = components.audioStack(params, mainScope, isDenoiseEnabled = { mediaCtl.deNoiseEnable.value })
    lateinit var rtm: RtmMessageHandler
    val participantManager = ParticipantManager()
    val timerManager = TimerManager(mainScope)

    val roomCtl = components.roomController(params, audio.handler, audio.processor)
    val room get() = roomCtl.room

    lateinit var activeSpeakers: StateFlow<List<Participant>>
    lateinit var mediaCtl: CallMediaController

    
    
    
    val mediaSendIssueState = MutableStateFlow(MediaSendIssueState.NONE)
    val mediaSendIssue: StateFlow<MediaSendIssueState> = mediaSendIssueState.asStateFlow()

    val feedbackBinder = CallFeedbackBinder(
        timerManager = timerManager,
        callFeedbackManager = deps.callFeedbackManager,
        roomIdGetter = { roomId },
    )

    val connectionCoordinator = CallConnectionCoordinator(
        appContext = deps.application,
        roomCtl = roomCtl,
        callTlsProvider = deps.callTlsProvider,
        statisticsLogManager = deps.statisticsLogManager,
        authTokenProvider = deps.authTokenProvider,
    )

    val screenSharePreWarmer = ScreenSharePreWarmer(mainScope)

    val timeoutMonitor = CallTimeoutMonitor(
        roomCtl = roomCtl,
        callTimeoutManager = deps.callTimeoutManager,
        onGoingCallStateManager = deps.onGoingCallStateManager,
        callRole = params.callRole,
        roomIdGetter = { roomId },
        onTimeout = ::onTimeout,
    )

    val criticalAlertDispatcher by lazy {
        CriticalAlertDispatcher(
            scope = mainScope,
            httpClientProvider = { deps.httpClient.get() },
            callToChatController = deps.callToChatController,
            participantManager = participantManager,
            uiState = uiState,
            room = room,
            roomIdGetter = { roomId },
            showBarrage = { p, m -> showBarrage(p, m) },
            showToast = ::showToast,
            messagingGate = deps.messagingGate,
        )
    }

    val speakerState = SpeakerStateHolder(
        scope = mainScope,
        roomCtl = roomCtl,
        onGoingCallStateManager = deps.onGoingCallStateManager,
    )

    private val screenShareFallback by lazy {
        ScreenShareFallbackSource(mainScope, room.localParticipant)
    }

    
    private val shareSpeaker = MutableStateFlow<String?>(null)

    val screenShareSpeakerLazy = lazy {
        ScreenShareFloatingSpeakerStateHolder(
            scope = mainScope,
            participantManager = participantManager,
            speakingEnabled = uiState.speakingEnabled,
            contactorCacheManager = deps.contactorCacheManager,
            explicitFallback = screenShareFallback.flow,
            layoutSpeaker = shareSpeaker.asStateFlow(),
        )
    }
    val screenShareFloatingSpeakerStateHolder by screenShareSpeakerLazy

    val cleanupExecutor = CallCleanupExecutor()

    
    val proximity = components.proximity(
        mainScope,
        combine(audio.deviceManager.routeSnapshot, roomCtl.cameraEnabled, uiState.isShareScreening, ::shouldHoldProximityLock),
    )

    private val roomEventHost by lazy {
        RoomEventHost(
            showBarrageFn = ::showBarrage,
            setMicEnabledFn = { e, pm, sb -> setMicEnabled(e, pm, sb) },
            setCameraEnabledFn = { e -> setCameraEnabled(e) },
            resetNoBodySpeakCheckFn = ::resetNoBodySpeakCheck,
            onRemoteEndRequestedFn = ::onRemoteEndRequested,
            stopRingToneAndTimeoutCheckFn = ::stopRingToneAndTimeoutCheck,
            stopCallerRingbackAndTimeoutCheckFn = ::stopCallerRingbackAndTimeoutCheck,
            resolveCallTypeFn = { callTypeCoordinator.resolveNow() },
            handleConnectedStateFn = ::handleConnectedState,
            startCallDurationTimerFn = ::startCallDurationTimer,
            onFeedbackIdentityResolvedFn = feedbackBinder::onIdentityResolved,
            onNetworkPoorStateChangedFn = { feedbackBinder.currentCallNetworkPoor = it },
            getCurrentCallTypeFn = ::getCurrentCallType,
            getCurrentRoomIdFn = { roomId },
        )
    }

    val networkQualityLazy = lazy {
        NetworkQualityCoordinator.create(mainScope, room, uiState, mediaSendIssue)
    }
    val networkQualityCoordinator by networkQualityLazy

    val roomEventDispatcherLazy = lazy {
        RoomEventDispatcher(
            scope = mainScope,
            room = room,
            roomCtl = roomCtl,
            rtm = rtm,
            connectionCoordinator = connectionCoordinator,
            uiState = uiState,
            participantManager = participantManager,
            screenSharePreWarmer = screenSharePreWarmer,
            timeoutMonitor = timeoutMonitor,
            timerManager = timerManager,
            speakerState = speakerState,
            callDataManager = deps.callDataManager,
            statisticsLogManager = deps.statisticsLogManager,
            mySelfId = mySelfId,
            networkQuality = networkQualityCoordinator,
            host = roomEventHost,
        )
    }
    val roomEventDispatcher by roomEventDispatcherLazy

    val callTypeCoordinatorLazy = lazy {
        CallTypeCoordinator(
            scope = mainScope,
            
            
            
            
            roomProvider = { roomCtl.roomOrNull() },
            roomCtl = roomCtl,
            callDataManager = deps.callDataManager,
            contactorCacheManager = deps.contactorCacheManager,
            callIntent = params.callIntent,
            callRole = params.callRole,
            mySelfId = mySelfId,
            json = json,
            roomIdGetter = { roomId },
        )
    }
    val callTypeCoordinator by callTypeCoordinatorLazy

    val sessionStarter by lazy {
        CallSessionStarter(
            scope = mainScope,
            room = room,
            roomCtl = roomCtl,
            connectionCoordinator = connectionCoordinator,
            onGoingCallStateManager = deps.onGoingCallStateManager,
            publisher = publisher,
            callRingtoneManager = deps.callRingtoneManager,
            callDataManager = deps.callDataManager,
            contactorCacheManager = deps.contactorCacheManager,
            callToChatController = deps.callToChatController,
            messageEncryptor = deps.messageEncryptor,
            callIntent = params.callIntent,
            e2eeEnable = params.e2eeEnable,
            mySelfId = mySelfId,
            createCallMsgConfig = params.callConfig.createCallMsg,
            onRoomIdAssigned = ::onRoomIdAssigned,
            onE2eeKeyAssigned = ::onE2eeKeyAssigned,
            isSessionEnding = { endController.isEnding },
            callerRingback = callerRingback,
        )
    }

    private val callerRingback = CallerRingback(deps.callRingtoneManager)

    private val exitSession = object : CallExitSession {
        override val callStatus get() = roomCtl.callStatus
        override fun getCurrentCallUidList() = currentCallUidList()
        override fun sendEndCall(onComplete: (Boolean) -> Unit) = rtm.sendEndCall(onComplete)
    }

    val endController: CallEndController = CallEndController(
        params = params,
        ogsm = deps.onGoingCallStateManager,
        publisher = publisher,
        ringtone = deps.callRingtoneManager,
        callDurationSeconds = { timerManager.callDurationSeconds.value },
        currentCallType = ::getCurrentCallType,
        stopForegroundService = { tag -> deps.ongoingCallService.stop(sessionId, tag) },
        exitHandlerFactory = { ctx ->
            CallExitHandler(
                session = exitSession,
                callToChatController = deps.callToChatController,
                onGoingCallStateManager = deps.onGoingCallStateManager,
                publisher = publisher,
                callDataManager = deps.callDataManager,
                callIntent = params.callIntent,
                callRole = params.callRole,
                context = ctx,
                onEndCall = { endController.finish(CallFinishRequest.LOCAL) },
            )
        },
        endSessionWithoutUi = endSessionWithoutUi,
        
        showToast = { res -> mainScope.launch { ToastUtil.show(ResUtils.getString(res)) } },
        scope = mainScope,
    )

    
    val roomWiringStarted = AtomicBoolean(false)

    
    fun startPhaseA(ioLaunchScope: CoroutineScope) {
        
        deps.statisticsLogManager.claimRoom(sessionId)
        LCallEngine.claimConnectedServerUrl(sessionId)
        publishIdentity()
        publisher.beginPresence()
        
        deps.onGoingCallStateManager.setNeedAppLock(params.needAppLock)
        proximity.start()
        components.registerCameraProvider(ioLaunchScope)
        audio.setup.start()
        deps.contactorCacheManager.startParticipantObservation(mainScope)
        
        mainScope.launch {
            roomCtl.callStatus.collect { status -> publisher.publish { updateCallStatus(status) } }
        }
        
        mainScope.launch {
            roomCtl.callType.collect { type -> publisher.publish { setCallType(type) } }
        }
    }

    




    private fun publishIdentity() = publisher.publish {
        val intent = params.callIntent
        if (intent.action == CallIntent.Action.START_CALL) {
            intent.clientCallId
                ?.takeIf { it != deps.onGoingCallStateManager.getClientCallId() }
                ?.let(::setClientCallId)
        }
        if (intent.action == CallIntent.Action.START_CALL || intent.action == CallIntent.Action.JOIN_CALL) {
            setConversationId(intent.conversationId)
        }
    }

    fun onRoomIdAssigned(rid: String) {
        roomId = rid
        deps.statisticsLogManager.setRoomId(rid)
    }

    fun onE2eeKeyAssigned(key: ByteArray) {
        e2eeKey = key
    }

    




    fun applyInitialVoicePreset() {
        val preset = params.initialVoicePreset
        L.i { "[call] LCallViewModel applyInitialVoicePreset preset=${preset.sdkKey}" }
        audio.deviceManager.switchVoicePreset(preset)
        mediaCtl.setVoicePreset(preset)
    }

    
    fun showBarrage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) {
        val identity = participant.identity?.value ?: return
        if (message.isEmpty()) return
        mainScope.launch(Dispatchers.IO) {
            CallBarrageFormatter.format(identity, message, type, params.callConfig, deps.contactorCacheManager)
                ?.let { uiState.emit(CallSessionUiEvent.Barrage(it)) }
        }
    }

    
    fun showToast(message: String) {
        if (uiState.hasSubscriber) uiState.emit(CallSessionUiEvent.Toast(message)) else ToastUtil.show(message)
    }

    fun resetNoBodySpeakCheck() {
        
        
        
        val snapshot = participantManager.participants.value
        val hasRemote = snapshot.any { it is RemoteParticipant }
        val isSilent = snapshot.firstOrNull { it.isMicrophoneEnabled } == null || activeSpeakers.value.isEmpty()
        speakerState.reset(hasRemote, isSilent, params.callConfig)
    }

    






    private fun onRemoteEndRequested(roomId: String) {
        mainScope.launch(Dispatchers.Main) { endController.onRemoteControl(CallActionType.HANGUP, roomId) }
    }

    
    private fun onTimeout(roomId: String) {
        mainScope.launch(Dispatchers.Main) { endController.onTimeout(roomId) }
    }

    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        mediaCtl.setMicEnabled(enabled, publishMuted, isShowBarrage)

    fun setCameraEnabled(enabled: Boolean) = mediaCtl.setCameraEnabled(enabled)

    






    fun handleConnectedState() {
        roomCtl.updateCallStatus(CallStatus.CONNECTED)
        if (getCurrentCallType() != CallType.ONE_ON_ONE.type) startCallDurationTimer()
    }

    fun startCallDurationTimer() {
        
        
        
        
        var connectedAt = 0L
        publisher.publish { connectedAt = markCallConnected() }
        timerManager.startCallTimer(connectedAt.takeIf { it > 0L } ?: System.currentTimeMillis()) { show ->
            val rid = roomId ?: return@startCallTimer
            publisher.publish { updateCallingTime(rid, show) }
        }
    }

    fun stopRingToneAndTimeoutCheck() {
        deps.callRingtoneManager.stopRingTone()
        deps.callVibrationManager.stopVibration()
        timeoutMonitor.cancelIfActive()
    }

    
    fun stopCallerRingbackAndTimeoutCheck() {
        callerRingback.stop(tag = "callee already in room")
        timeoutMonitor.cancelIfActive()
    }

    fun updateScreenShareFallback(participant: Participant) = screenShareFallback.update(participant)
    fun setShareSpeaker(id: String?) { shareSpeaker.value = id }
    fun shouldTriggerFeedbackView() = feedbackBinder.maybeTrigger()

    







    fun getCurrentCallType(): String = roomCtl.callType.value

    
    
    
    
    fun currentCallUidList(): List<String> = participantManager.participants.value
        .filterIsInstance<RemoteParticipant>()
        .mapNotNull { it.identity?.value }
        .map { userId -> if (userId.contains(".")) userId.split(".")[0] else userId }

    
    fun clearE2eeKey() {
        e2eeKey?.fill(0)
        e2eeKey = null
    }
}
