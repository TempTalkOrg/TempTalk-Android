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

/**
 * The collaborators of one call session, built in a fixed construction order with lazies and
 * once-only gates. Phase A ([startPhaseA]) runs in the session's
 * start; Phase B is [startRoomDependentWiring] in `CallRoomWiring.kt`; the end of the call is
 * [runCallLevelTeardown] and [buildCleanupSteps] in `CallSessionTeardown.kt`.
 *
 * Every collaborator takes [mainScope] (same `Job` as the
 * session, `Main.immediate`), so none of them changes its effective dispatcher. Everything the
 * session shows on a call page goes through [uiState]; the graph never references the UI.
 */
@Suppress("TooManyFunctions") // session state logic is deliberately kept in one graph
internal class CallSessionGraph(
    val params: CallSessionParams,
    val deps: CallSessionDeps,
    val components: CallSessionComponentsFactory,
    val mainScope: CoroutineScope,
    /** The owning session's id: the key of its projection fence, its service and its process-wide slots. */
    val sessionId: Long,
    /** Ends the owning session; bound by [CallSession] (posts to main when called off main). */
    private val endSessionWithoutUi: () -> Unit,
) {
    /** First member: every collaborator below writes its UI output here. */
    val uiState = CallSessionUiState()

    /** The session's only write path to OnGoingCallStateManager's session fields. */
    val publisher = OngoingCallPublisher(sessionId, deps.onGoingCallStateManager)

    // coerceInputValues so an explicit `null` for a non-null field falls back to its default rather
    // than failing the decode — room metadata must parse field-by-field (see [RoomMetadataPatch]).
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val mySelfId: String by lazy { components.selfId() }

    // Written from CallSessionStarter's IO coroutine, read from main and other threads.
    @Volatile var roomId: String? = null
        private set

    @Volatile var e2eeKey: ByteArray? = null
        private set

    val audio = components.audioStack(params, mainScope, isDenoiseEnabled = { mediaCtl.deNoiseEnable.value })
    lateinit var rtm: RtmMessageHandler
    val participantManager = ParticipantManager(mainScope)
    val timerManager = TimerManager(mainScope)

    val roomCtl = components.roomController(params, audio.handler, audio.processor)
    val room get() = roomCtl.room

    lateinit var activeSpeakers: StateFlow<List<Participant>>
    lateinit var mediaCtl: CallMediaController

    // Room CONNECTED but the local uplink (publisher) is degraded — "others can't hear/see me".
    // IDLE (no local publish demand) and CONNECTED are healthy; connecting/reconnecting rooms keep
    // showing the existing connection UI instead.
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
        )
    }

    val speakerState = SpeakerStateHolder(
        scope = mainScope,
        roomCtl = roomCtl,
        participantManager = participantManager,
        onGoingCallStateManager = deps.onGoingCallStateManager,
    )

    private val screenShareFallback by lazy {
        ScreenShareFallbackSource(mainScope, room.localParticipant)
    }

    val screenShareSpeakerLazy = lazy {
        ScreenShareFloatingSpeakerStateHolder(
            scope = mainScope,
            participantManager = participantManager,
            speakingEnabled = uiState.speakingEnabled,
            contactorCacheManager = deps.contactorCacheManager,
            explicitFallback = screenShareFallback.flow,
        )
    }
    val screenShareFloatingSpeakerStateHolder by screenShareSpeakerLazy

    val cleanupExecutor = CallCleanupExecutor()

    /** Screen off at the ear for the whole call, page or not; released in [runCallLevelTeardown]. */
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
            // Non-throwing accessor: the coordinator's room.metadata collector lives in the
            // session scope and can process a late emission after disconnectAndRelease() flipped
            // `released`; the fail-loud `room` getter would crash there ("room accessed after
            // release"). roomOrNull() yields null once released so that emission is dropped.
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
        )
    }

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
        // finish() can run off main (the exit handler's appScope coroutines): toast on main.
        showToast = { res -> mainScope.launch { ToastUtil.show(ResUtils.getString(res)) } },
        scope = mainScope,
    )

    /** Phase B once-only gate ([startRoomDependentWiring]). */
    val roomWiringStarted = AtomicBoolean(false)

    /**
     * Phase A: runs in the session's start, on main, after the session bound the projection fence.
     * The call is shown from here on.
     */
    fun startPhaseA(ioLaunchScope: CoroutineScope) {
        // Before any writer: the process-wide statistics room ids and connected URL are this call's.
        deps.statisticsLogManager.claimRoom(sessionId)
        LCallEngine.claimConnectedServerUrl(sessionId)
        publishIdentity()
        publisher.beginPresence()
        // The app-lock check reads this while no call page is up; a page writes its own on create.
        deps.onGoingCallStateManager.setNeedAppLock(params.needAppLock)
        proximity.start()
        components.registerCameraProvider(ioLaunchScope)
        audio.setup.start()
        deps.contactorCacheManager.startParticipantObservation(mainScope)
        // The foreground service renders the ongoing-call notification from this status.
        mainScope.launch {
            roomCtl.callStatus.collect { status -> publisher.publish { updateCallStatus(status) } }
        }
        // Readers outside the call page (chat header, notifications, calls from chat) see the live type.
        mainScope.launch {
            roomCtl.callType.collect { type -> publisher.publish { setCallType(type) } }
        }
    }

    /**
     * The call's identity from the start/join intent. A start writes its client call id only when the
     * projection does not hold it yet, because that write clears the pre-connect cancel latch: a new
     * call clears a stale latch, while a page re-entry keeps a pending cancel.
     */
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

    /**
     * Apply the user's global voice-changer preference at call start.
     * Safe to call for ORIGINAL too — it resets any lingering SoundTouch state
     * on the shared audio processor.
     */
    fun applyInitialVoicePreset() {
        val preset = params.initialVoicePreset
        L.i { "[call] LCallViewModel applyInitialVoicePreset preset=${preset.sdkKey}" }
        audio.deviceManager.switchVoicePreset(preset)
        mediaCtl.setVoicePreset(preset)
    }

    /** Formats on IO (contact-name lookup) in the session `Job`, then hands the result to the UI. */
    fun showBarrage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) {
        val identity = participant.identity?.value ?: return
        if (message.isEmpty()) return
        mainScope.launch(Dispatchers.IO) {
            CallBarrageFormatter.format(identity, message, type, params.callConfig, deps.contactorCacheManager)
                ?.let { uiState.emit(CallSessionUiEvent.Barrage(it)) }
        }
    }

    /** Through the call page when one collects the session's events, else as an app toast. */
    fun showToast(message: String) {
        if (uiState.hasSubscriber) uiState.emit(CallSessionUiEvent.Toast(message)) else ToastUtil.show(message)
    }

    fun resetNoBodySpeakCheck() {
        // Read the participants StateFlow (local + remotes) instead of the fail-loud room
        // getter: this is invoked from the end-reminder dialog's "Continue" path and from
        // 1v1 room events, both of which can race with call teardown (released=true).
        val snapshot = participantManager.participants.value
        val hasRemote = snapshot.any { it is RemoteParticipant }
        val isSilent = snapshot.firstOrNull { it.isMicrophoneEnabled } == null || activeSpeakers.value.isEmpty()
        speakerState.reset(hasRemote, isSilent, params.callConfig)
    }

    /**
     * [RoomEventHost] remote-end seam. RTM END_CALL calls it on the RTM IO coroutine, server-end-call
     * on the main room-event collector: both are posted (`Dispatchers.Main`, not `.immediate`) on this
     * session's `Job`, so the end controller always runs on main after the current message, never
     * re-entrantly inside the handler that saw the end. A cancelled `Job` (session already cleared)
     * drops it.
     */
    private fun onRemoteEndRequested(roomId: String) {
        mainScope.launch(Dispatchers.Main) { endController.onRemoteControl(CallActionType.HANGUP, roomId) }
    }

    /** [CallTimeoutMonitor] seam: posted to main on this session's `Job`, like [onRemoteEndRequested]. */
    private fun onTimeout(roomId: String) {
        mainScope.launch(Dispatchers.Main) { endController.onTimeout(roomId) }
    }

    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        mediaCtl.setMicEnabled(enabled, publishMuted, isShowBarrage)

    fun setCameraEnabled(enabled: Boolean) = mediaCtl.setCameraEnabled(enabled)

    /**
     * Enters the connected state. For 1v1 the duration timer is NOT started here — the remote can be
     * in the room over signaling while its RTC transport is still connecting, and counting from that
     * moment tells the user they can talk when the remote cannot hear them yet. [RoomEventDispatcher]
     * gates that call's timer on the remote reaching ACTIVE and subscribing the local microphone;
     * group/instant keep the old behaviour of counting as soon as the room is up.
     */
    fun handleConnectedState() {
        roomCtl.updateCallStatus(CallStatus.CONNECTED)
        if (getCurrentCallType() != CallType.ONE_ON_ONE.type) startCallDurationTimer()
    }

    fun startCallDurationTimer() {
        // One instant drives both clocks: the timer recomputes the on-screen duration from the
        // connect instant the notification publishes as `when`, so the call UI, the conversation-list
        // call bar and the system call chip cannot drift apart. It is read inside the fenced write; a
        // session that no longer owns the projection counts from now, not from another session's.
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

    fun updateScreenShareFallback(participant: Participant) = screenShareFallback.update(participant)
    fun shouldTriggerFeedbackView() = feedbackBinder.maybeTrigger()

    /**
     * The single in-call source of truth for the meeting type, written only by [CallTypeCoordinator].
     *
     * Not `CallData.type`: the UI never consults it and it is not populated until an outbound call
     * has processed `ttCallResp`, so room-event handlers could branch on an empty type while the UI
     * already showed the right one. Reading `roomCtl.callType` puts both on the same value; the coordinator mirrors it onto
     * `CallData.type` for the exit-semantics path.
     */
    fun getCurrentCallType(): String = roomCtl.callType.value

    // Uses the participants StateFlow rather than the fail-loud room getter: this is invoked
    // from CallExitHandler's async hangup callback, which can run after a concurrent path has
    // already released the room (remote onLeave / the session end's cleanup). The snapshot also
    // retains the last-known remotes even after room.remoteParticipants is cleared by disconnect.
    fun currentCallUidList(): List<String> = participantManager.participants.value
        .filterIsInstance<RemoteParticipant>()
        .mapNotNull { it.identity?.value }
        .map { userId -> if (userId.contains(".")) userId.split(".")[0] else userId }

    /** Zeroes and drops the call's E2EE key (a cleanup step). */
    fun clearE2eeKey() {
        e2eeKey?.fill(0)
        e2eeKey = null
    }
}
