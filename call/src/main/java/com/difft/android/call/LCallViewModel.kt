package com.difft.android.call

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RAISE_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.data.RtmMessage
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.ServerConnectionException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.HandsUpManager
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.RtmMessageHandler
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.util.StringUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.CriticalAlertRequestBody
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.google.gson.Gson
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.RoomException
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import livekit.org.webrtc.CameraXHelper
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.Volatile


class LCallViewModel (
    application: Application,
    audioProcessor: DenoisePluginAudioProcessor? = null,
    private val e2eeEnable: Boolean = false,
    private val callIntent: CallIntent,
    private val callConfig: CallConfig,
    private val callRole: CallRole,
) : AndroidViewModel(application) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        var callToChatController: LCallToChatController
        var messageEncryptor: INewMessageContentEncryptor

        @ChativeHttpClientModule.Chat
        fun httpClient(): ChativeHttpClient
    }

    private val callToChatController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callToChatController
    }
    private val messageEncryptor: INewMessageContentEncryptor by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).messageEncryptor
    }
    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private val mySelfId: String by lazy { globalServices.myId }
    val conversationId = callIntent.conversationId
    fun getCallRole() = callRole
    private var roomId: String? = null
    private var e2eeKey: ByteArray? = null
    private var _callRoomName: String = callIntent.roomName

    // ---- Managers ----
    lateinit var rtm: RtmMessageHandler
    private val handsUpManager = HandsUpManager(viewModelScope, application)
    private val participantManager = ParticipantManager(viewModelScope)
    val callUiController = CallUiController()
    val timerManager = TimerManager(viewModelScope)
    val audioDeviceManager = AudioDeviceManager(application, callIntent.callType)
    val audioHandler get() = audioDeviceManager.audioHandler
    private val roomCtl = CallRoomController(
        appContext = application,
        scope = viewModelScope,
        callIntent = callIntent,
        audioHandler = audioHandler,
        audioProcessor = audioProcessor,
        e2eeEnable = e2eeEnable,
        decryptCallMKey = { eKey, eMKey -> messageEncryptor.decryptCallKey(eKey, eMKey) }
    )

    // ---- Managers ----
    private val activeSpeakers = room::activeSpeakers.flow

    val room get() = roomCtl.room
    val callStatus get() = roomCtl.callStatus
    val callType get() = roomCtl.callType
    val error get() = roomCtl.error
    val deNoiseEnable get() = audioDeviceManager.deNoiseEnable
    val isNoSpeakSoloTimeout get() = roomCtl.isNoSpeakSoloTimeout
    val micEnabled get() = roomCtl.micEnabled
    val cameraEnabled get() = roomCtl.cameraEnabled
    val participants get() = participantManager.participants
    val primarySpeaker get() = participantManager.primary
    val screenSharingUser get() = participantManager.screenSharingUser
    val currentAudioDevice get() = audioDeviceManager.selected
    val handsUpUserInfo get() = handsUpManager.handsUpUserInfo

    var userSid: String? = ""
    var userIdentity: String? = null
    var roomSid: String? = ""
    var currentCallNetworkPoor: Boolean = false

    // --------------- Internals ---------------
    @Volatile
    private var isRetryUrlConnecting = false
    private var isCallResourceReleased = false

    private var lastLocalPoorErrorTime: Long = 0L
    private val goodQualities = setOf(ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD)
    private val networkPoorInterval = 60_000L

    // --- no-speaking timeout detection ---
    private enum class TimeoutCheckState { NONE, PARTICIPANT_LEAVE, ONGOING_CALL }
    private var timeoutCheckState = TimeoutCheckState.NONE
    private var noSpeakJob: Job? = null
    private var isNoSpeakerChecking = false
    private var isOnePersonChecking = false

    // --- current speaker switch ---
    private var lastSpeakers: List<Participant>? = null
    private var hasSpeaker: Boolean = false
    private var debounceSpeakerUpdateJob: Job? = null


    init {
        // Init camera provider.
        initCameraProvider(application)

        // Init RTM handler.
        initRtmHandler()

        // Connect server room.
        connectToRoom(callIntent.callServerUrls, callIntent.startCallParams, LCallEngine.isUseQuicSignal())

        // Init audio device change listener.
        initAudioDeviceChangeListener(audioProcessor)

        // Handle start call response.
        handleStartCallResponse()

        // Handle room events.
        handleRoomEvents()

        // participant change listener.
        registerParticipantChangeListener()

        // Collect any changes in contacts.
        registerContactsUpdateListener()

        // Handle any changes in speakers.
        registerSpeakerChangeListener()

        // Collect any errors.
        registerErrorCollector()

        registerListenSwitchCallServer()

        registerListenSwitchConnectionType()

        checkCriticalAlertStatusById(callIntent)
    }

    fun getE2eeKey(): ByteArray? = e2eeKey

    fun getRoomId(): String? = roomId

    fun getCallRoomName(): String {
        val callType = getCurrentCallType()
        val participantNum = room.remoteParticipants.size + 1
        return if (callType == CallType.ONE_ON_ONE.type) _callRoomName else "$_callRoomName ($participantNum)"
    }

    /**
     * Initializes the camera provider for the application, which is required for accessing the device's camera functionality.
     */
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

    /**
     * Initializes the RTM (Real-Time Messaging) handler with encryption/decryption capabilities.
     */
    private fun initRtmHandler() {
        rtm = RtmMessageHandler(
            room = room,
            scope = viewModelScope,
            encryptor = { plain ->
                val localPrivateKey =
                    callToChatController.getLocalPrivateKey() ?: return@RtmMessageHandler null
                try {
                    messageEncryptor.encryptRtmMessage(
                        plain,
                        localPrivateKey,
                        e2eeKey ?: error("E2EE key not found")
                    )
                } catch (e: Exception) {
                    L.e { "[Call] LCallViewModel rtm encrypt error = ${e.message}" }
                    null
                }
            },
            decryptor = { participant, data ->
                val uid = participant.identity?.value ?: return@RtmMessageHandler null
                val pub =
                    callToChatController.getTheirPublicKey(uid) ?: return@RtmMessageHandler null
                try {
                    val plain = messageEncryptor.decryptRtmMessage(
                        data,
                        pub,
                        e2eeKey ?: error("E2EE key not found")
                    )
                    Json.decodeFromString<RtmMessage>(String(plain, Charsets.UTF_8))
                } catch (e: Exception) {
                    L.e { "[Call] LCallViewModel rtm decrypt error = ${e.message}" }
                    null
                }
            }
        )
    }

    /**
     * Attempts to connect to a call room using a list of provided URLs.
     */
    private fun connectToRoom(urls: List<String>, callParams: ByteArray?, useQuicSignal: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            if(callParams == null) {
                roomCtl.collectError(StartCallException(getString(R.string.call_params_startcall_exception_tip)))
                return@launch
            }
            if (urls.isEmpty()) {
                roomCtl.collectError(StartCallException(getString(R.string.call_params_url_exception_tip)))
                return@launch
            }
            for (url in urls) {
                try {
                    L.i { "[Call] LCallViewModel connectToRoom url = $$url" }
                    roomCtl.connect(url, callIntent.appToken, callParams, useQuicSignal) { t -> throw t }
                    setConnectedServerUrl(url)
                    isRetryUrlConnecting = false
                    return@launch
                } catch (e: Throwable) {
                    when (e) {
                        is SocketTimeoutException, is SSLHandshakeException, is UnknownHostException -> {
                            L.e { "[Call] LCallViewModel connectToRoom timeout url = $$url, error = ${e.message}" }
                            room.disconnect()
                            if (url == urls.lastOrNull()) {
                                isRetryUrlConnecting = false
                                roomCtl.collectError(e)

                            } else {
                                isRetryUrlConnecting = true
                                continue
                            }
                        }
                        is RoomException.NoAuthException, is RoomException.StartCallException -> {
                            isRetryUrlConnecting = false
                            roomCtl.collectError(StartCallException(e.message))
                            break
                        }
                        else -> {
                            isRetryUrlConnecting = false
                            roomCtl.collectError(ServerConnectionException(e.message))
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * Initializes the audio device change listener with optional denoise processing capabilities.
     */
    private fun initAudioDeviceChangeListener(audioProcessor: DenoisePluginAudioProcessor?) {
        viewModelScope.launch(Dispatchers.IO) {
            audioHandler.let { handler ->
                handler.audioDeviceChangeListener = object : AudioDeviceChangeListener {
                    override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) {
                        L.i { "[call] LCallViewModel audioDevice changeListener selectedAudioDevice = ${selectedAudioDevice?.name}" }
                        callConfig.let {
                            val excludedNameRegex = it.denoise?.bluetooth?.excludedNameRegex
                            val deviceName = selectedAudioDevice?.name
                            if (!excludedNameRegex.isNullOrEmpty() && !deviceName.isNullOrEmpty() &&
                                Regex(excludedNameRegex, RegexOption.IGNORE_CASE).containsMatchIn(deviceName)) {
                                L.i { "[call] LCallViewModel audioDevice is in excludedNameRegex = $excludedNameRegex" }
                                audioProcessor?.setEnabled(false)
                            } else {
                                audioProcessor?.setEnabled(deNoiseEnable.value)
                            }
                        }
                        audioDeviceManager.update(selectedAudioDevice)
                    }
                }
            }
        }
    }

    /**
     * Handles the response from starting a call, including validation and subsequent actions.
     */
    private fun handleStartCallResponse() {
        viewModelScope.launch(Dispatchers.IO) {
            val response = withTimeoutOrNull(15000L) { room::ttCallResp.flow.filterNotNull().first() }
            L.i { "[Call] LCallViewModel start call response callback." }
            if (response == null) {
                roomCtl.collectError(StartCallException("call response timeout"))
                return@launch
            }
            if (response.body == null || response.base.status != 0) {
                roomCtl.collectError(StartCallException("call response exception, status:${response.base.status}"))
                return@launch
            }
            roomId = response.body.roomId
            LCallActivity.setCurrentRoomId(roomId)
            val callerId = callIntent.callerId
            val callType = CallType.fromString(callIntent.callType) ?: CallType.ONE_ON_ONE
            val forWhat = when (callType) {
                CallType.GROUP -> For.Group(callIntent.conversationId!!)
                CallType.ONE_ON_ONE -> For.Account(callIntent.conversationId!!)
                else -> For.Account(callIntent.callerId)
            }

            if (e2eeEnable) {
                val mk = messageEncryptor.decryptCallKey(response.body.publicKey, response.body.emk)
                if (mk == null) {
                    roomCtl.collectError(StartCallException("e2ee key is null or emk missing"))
                    return@launch
                } else e2eeKey = mk
            }

            if (callIntent.action == CallIntent.Action.START_CALL) {
                startCallRingTone(callType)
                addStartCallData(forWhat, callerId, callIntent.roomName, response.body.roomId, callType, response.body.createdAt)
                sendStartCallTextMessage(forWhat, callType, response.body.systemShowTimestamp)
            } else if (callIntent.action == CallIntent.Action.JOIN_CALL) {
                response.body.roomId?.let { rid ->
                    sendJoinSyncControlMessage(forWhat, rid, callerId)
                    callToChatController.cancelNotificationById(rid.hashCode())
                }
            }
        }
    }

    /**
     * Initiates the outgoing call ringtone for the caller based on the specified call type.
     */
    private fun startCallRingTone(callType: CallType) {
        viewModelScope.launch(Dispatchers.IO) {
            val ringIntent = Intent().apply {
                putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callType.type)
                putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLER.type)
            }
            LCallManager.startRingTone(ringIntent)
        }
    }

    /**
     * Registers an error collector to handle and log errors from the ViewModel's error stream.
     */
    private fun registerErrorCollector() { viewModelScope.launch { error.collect { it?.let { L.e { it.stackTraceToString() } } } } }

    /**
     * Registers a participant change listener to monitor and manage call participant updates.
     */
    private fun registerParticipantChangeListener() {
        viewModelScope.launch {
            room::remoteParticipants.flow.map { remoteParticipants ->
                (listOf<Participant>(room.localParticipant) +
                        remoteParticipants
                            .keys
                            .sortedBy { it.value }
                            .mapNotNull { remoteParticipants[it] })
            }.collectLatest { updatedParticipants ->
                participantManager.setParticipants(updatedParticipants)
                participantManager.resortParticipants()
            }
        }
    }

    /**
     * Registers a listener for contact updates and maintains the call contact cache.
     */
    private fun registerContactsUpdateListener() {
        viewModelScope.launch(Dispatchers.IO) { callToChatController.getContactsUpdateListener().collect { LCallManager.updateCallContactorCache(it) } }
    }

    /**
     * Registers a speaker change listener to monitor active speakers and handle speaking timeout scenarios.
     */
    private fun registerSpeakerChangeListener() {
        viewModelScope.launch { combine(participants, activeSpeakers) { p, s -> p to s }.collect { (pList, speakers) -> checkNoSpeakOrOnePersonTimeout(pList, speakers) } }
    }

    /**
     * Checks for silent/no-speaker or single-participant timeout scenarios in a call and triggers appropriate UI updates.
     */
    private fun checkNoSpeakOrOnePersonTimeout(participantsList: List<Participant>, speakers: List<Participant>) {
        callConfig.let { config ->
            val hasRemote = room.remoteParticipants.isNotEmpty()
            val isSilent = participantsList.firstOrNull { it.isMicrophoneEnabled } == null || speakers.isEmpty()
            if (hasRemote) {
                if (isSilent) {

                    if (isOnePersonChecking) { noSpeakJob?.cancel(); isOnePersonChecking = false; roomCtl.updateNoSpeakSoloTimeout(false) }
                    if (!isNoSpeakerChecking && isTimeoutCheckApplicable()) {
                        isNoSpeakerChecking = true; isOnePersonChecking = false
                        noSpeakJob?.cancel()
                        noSpeakJob = viewModelScope.launch(Dispatchers.IO) {
                            delay(config.autoLeave.promptReminder.silenceTimeout)
                            withContext(Dispatchers.Main) { roomCtl.updateNoSpeakSoloTimeout(true) }
                        }
                    }
                } else {
                    noSpeakJob?.cancel(); isNoSpeakerChecking = false; isOnePersonChecking = false; roomCtl.updateNoSpeakSoloTimeout(false)
                }
            } else {
                if (!isOnePersonChecking && isTimeoutCheckApplicable()) {
                    isOnePersonChecking = true; isNoSpeakerChecking = false
                    noSpeakJob?.cancel()
                    noSpeakJob = viewModelScope.launch(Dispatchers.IO) {
                        delay(config.autoLeave.promptReminder.soloMemberTimeout)
                        withContext(Dispatchers.Main) { roomCtl.updateNoSpeakSoloTimeout(true) }
                    }
                }
            }
        }
    }

    private fun isTimeoutCheckApplicable() = LCallActivity.isInCalling() && !LCallActivity.isInCallEnding()

    /**
     * Handles various room events from the livekit sdk.
     */
    private fun handleRoomEvents() {
        viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Disconnected -> {
                        L.i { "[Call] LCallViewModel room event disconnected, message = ${event.reason}" }
                        if (event.reason != DisconnectReason.CLIENT_INITIATED) {
                            if (event.reason == DisconnectReason.RECONNECT_FAILED) roomCtl.updateCallStatus(CallStatus.RECONNECT_FAILED)
                            roomCtl.collectError(DisconnectException(event.reason.name))
                        } else {
                            if(roomCtl.callStatus.value == CallStatus.SWITCHING_SERVER) return@collect
                            roomCtl.updateCallStatus(CallStatus.DISCONNECTED)
                        }
                    }
                    is RoomEvent.FailedToConnect -> {
                        if (isRetryUrlConnecting) return@collect
                        L.i { "[Call] LCallViewModel room event disconnected, message = ${event.error}." }
                        roomCtl.updateCallStatus(CallStatus.CONNECTED_FAILED)
                        roomCtl.collectError(event.error)
                    }
                    is RoomEvent.DataReceived -> handleDataReceived(event)
                    is RoomEvent.ParticipantDisconnected -> onParticipantDisconnected()
                    is RoomEvent.ParticipantConnected -> onParticipantConnected(event.participant)
                    is RoomEvent.Reconnected -> {
                        L.i { "[Call] LCallViewModel room event reconnected." }
                        cancelCallTimeoutCheck(); roomCtl.updateCallStatus(CallStatus.RECONNECTED)
                    }
                    is RoomEvent.Reconnecting -> {
                        L.i { "[Call] LCallViewModel room event reconnecting." }
                        cancelCallTimeoutCheck(); roomCtl.updateCallStatus(CallStatus.RECONNECTING)
                    }
                    is RoomEvent.Connected -> onConnected()
                    is RoomEvent.TrackMuted -> onTrackMuted(event)
                    is RoomEvent.TrackUnmuted -> onTrackUnmuted(event)
                    is RoomEvent.TrackSubscribed -> { checkRemoteUserScreenShare(event.participant); if (event.publication.source == Track.Source.CAMERA && !event.publication.muted) participantManager.resortParticipants() }
                    is RoomEvent.TrackUnsubscribed -> checkRemoteUserScreenShare(event.participant)
                    is RoomEvent.RoomMetadataChanged -> refreshRoomMetadata()
                    is RoomEvent.ConnectionQualityChanged -> onConnectionQualityChanged(event.participant, event.quality)
                    is RoomEvent.ActiveSpeakersChanged -> onActiveSpeakersChanged(event.speakers)
                    else -> {}
                }
            }
        }
    }


    /**
     * Processes incoming real-time data messages received during a call and executes corresponding actions.
     */
    private fun handleDataReceived(it: RoomEvent.DataReceived) {
        rtm.handleDataReceived(
            event = it,
            onChat = { p, text -> showCallBarrageMessage(p, text) },
            onMuteMe = { setMicEnabled(false) },
            onResumeMe = {
                if (getCurrentCallType() == CallType.ONE_ON_ONE.type) resetNoBodySpeakCheck()
            },
            onEndCall = {
                roomId?.let { rid -> LCallManager.removeCallData(rid); sendHangUpBroadcast(rid) }
            },
            onCountDown = { data, topic ->
                when (topic) {
                    RTM_MESSAGE_TOPIC_SET_COUNTDOWN, RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN -> {
                        val left = calculateCountDownDuration(data.expiredTimeMs, data.currentTimeMs)
                        data.operatorIdentity.takeIf { it.isNotEmpty() }?.let { opId ->
                            room.remoteParticipants[Participant.Identity(opId)]?.let { p -> showCallBarrageMessage(p, getString(R.string.call_barrage_message_countdown_timer)) }
                        }
                        timerManager.startCountdown(left, onEnded = { }, onTick = { callUiController.setCountDownDurationStr(it) })
                    }
                    RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN -> {
                        val left = calculateCountDownDuration(data.expiredTimeMs, data.currentTimeMs)
                        timerManager.startCountdown(left, onEnded = { }, onTick = { callUiController.setCountDownDurationStr(it)  })
                    }
                    RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> timerManager.stopCountdown()
                }
            },
            onHandsUp =  { data, topic ->
                // handle the logic for raising or cancelling hands up
                val hands = data.hands ?: emptyList()
                when (topic) {
                    RTM_MESSAGE_TOPIC_RAISE_HANDS_UP -> {
                        // handle raise hands up logic here
                        handsUpManager.updateHandsUpParticipants(hands)
                    }
                    RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP -> {
                        // handle cancel hands up logic here
                        handsUpManager.updateHandsUpParticipants(hands)

                        if (callUiController.handsUpEnabled.value && room.localParticipant.identity?.value !in hands.map { it.identity }) {
                            callUiController.setHandsUpEnable(false)
                        }
                    }
                }
            }
        )
    }

    /**
     * Displays a call barrage message (floating chat message) in the call UI for a specific participant.
     */
    fun showCallBarrageMessage(participant: Participant, message: String) {
        participant.identity?.value?.let { identityValue ->
            viewModelScope.launch(Dispatchers.IO) {
                val name = LCallManager.getDisplayName(identityValue) ?: LCallManager.convertToBase58UserName(identityValue) ?: identityValue
                val showName = StringUtil.getShowUserName(name, 14)
                val barrageMessage = BarrageMessage(showName, message, System.currentTimeMillis())
                withContext(Dispatchers.Main) { callUiController.setBarrageMessage(barrageMessage) }
            }
        }
    }

    /**
     * Resets the silent/no-speaker detection timer and rechecks the current call scenario.
     */
    fun resetNoBodySpeakCheck() {
        noSpeakJob?.cancel(); isNoSpeakerChecking = false; isOnePersonChecking = false
        roomCtl.updateNoSpeakSoloTimeout(false)
        val currentParticipants = listOf<Participant>(room.localParticipant) + room.remoteParticipants.values.toList()
        checkNoSpeakOrOnePersonTimeout(currentParticipants, activeSpeakers.value)
    }

    /**
     * Sends a broadcast intent to notify the system about a call hang-up event.
     */
    private fun sendHangUpBroadcast(roomId: String) {
        val intent = Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
            putExtra(LCallActivity.EXTRA_CONTROL_TYPE, CallActionType.HANGUP.type)
            putExtra(LCallActivity.EXTRA_PARAM_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
        ApplicationHelper.instance.sendBroadcast(intent)
    }

    /**
     * Enables or disables the local participant's microphone with optional publish mute control.
     */
    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) {
        if (!PermissionUtil.arePermissionsGranted(ApplicationHelper.instance, arrayOf(Manifest.permission.RECORD_AUDIO))) {
            L.e { "[call] LCallViewModel setMicEnabled no permission" }
            return
        }

        viewModelScope.launch {
            try {
                if (room.localParticipant.audioTrackPublications.isEmpty()) {
                    if (roomCtl.roomMetadata.value?.canPublishAudio == true) {
                        setMicrophone(enabled, publishMuted)
                        if(isShowBarrage) showMuteBarrageByMicEnabled(enabled)
                    } else {
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                } else {
                    setMicrophone(enabled, publishMuted)
                    if(isShowBarrage) showMuteBarrageByMicEnabled(enabled)
                }
            } catch (e: Exception) {
                L.e { "[call] LCallViewModel setMicEnabled error:${e.message}" }
            }
        }
    }

    private fun setMicrophone(enabled: Boolean, publishMuted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        try {
            room.localParticipant.setMicrophoneEnabled(enabled, publishMuted)
            roomCtl.updateMicEnabled(enabled && !publishMuted)
        } catch (e: Throwable) {
            L.e { "[call] LCallViewModel setMicrophone error:${e.message}" }
        }
    }

    /**
     * Shows a barrage message indicating whether the microphone is enabled or disabled.
     */
    private fun showMuteBarrageByMicEnabled(enabled: Boolean) {
        val message = if (enabled) getString(R.string.call_barrage_message_open_mic) else getString(R.string.call_barrage_message_close_mic)
        showCallBarrageMessage(room.localParticipant, message)
    }

    /**
     * Handles the state when the call is successfully connected.
     */
    private fun handleConnectedState() {
        roomCtl.updateCallStatus(CallStatus.CONNECTED)
        timerManager.startCallTimer { show ->
            roomId?.let { LCallManager.updateCallingTime(it, show) }
        }
    }

    /**
     * Switches the current call type to INSTANT call mode and updates relevant call metadata.
     */
    private fun switchToInstantCall() {
        val callingListData = LCallManager.getCallListData()
        roomId?.let { rid ->
            viewModelScope.launch(Dispatchers.IO) {
                if (rid.isNotEmpty() && callingListData?.containsKey(rid) == true) {
                    _callRoomName = if(callRole == CallRole.CALLER)
                        "${LCallManager.getDisplayNameById(mySelfId)}${getString(R.string.call_instant_call_title)}"
                    else
                        "${callIntent.roomName}${getString(R.string.call_instant_call_title)}"

                    callingListData[rid]?.type = CallType.INSTANT.type
                    callingListData[rid]?.callName = _callRoomName
                    LCallManager.updateCallingListData(callingListData)
                    roomCtl.updateCallType(CallType.INSTANT.type)
                }
            }
        }
    }

    /**
     * Refreshes the room metadata by parsing and updating the current room's metadata information.
     */
    private fun refreshRoomMetadata() {
        if (!room.metadata.isNullOrEmpty()) roomCtl.updateRoomMetadata(Gson().fromJson(room.metadata, RoomMetadata::class.java))
    }

    /**
     * Handles the room connection event when the call is successfully connected.
     */
    private fun onConnected() {
        roomId?.let { rid ->
            L.i { "[Call] LCallViewModel room event connected." }
            userSid = room.localParticipant.sid.value
            userIdentity = room.localParticipant.identity?.value
            roomSid = room.sid?.sid
            LCallManager.updateCallingState(rid, isInCalling = true)
            if (getCurrentCallType() == CallType.ONE_ON_ONE.type) {
                setMicEnabled(true)
                if (room.remoteParticipants.size > 1) { switchToInstantCall(); handleConnectedState() }
                else if (room.remoteParticipants.size == 1) handleConnectedState()
                else start1V1CallTimeout(rid)
            } else {
                handleConnectedState(); setMicEnabled(true, publishMuted = true, isShowBarrage = false)
            }
            refreshRoomMetadata()
        }
    }

    /**
     * Handles the event when a new participant connects to the call.
     */
    private fun onParticipantConnected(participant: Participant) {
        showCallBarrageMessage(participant, getString(R.string.call_barrage_message_join))
        LCallManager.stopRingTone(); LCallManager.stopVibration(); cancelCallTimeoutCheck()
        if (getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            handleConnectedState()
            if(callIntent.callRole == CallRole.CALLER.type) callUiController.setCriticalAlertEnable(false)
            if (room.remoteParticipants.size > 1) switchToInstantCall()
        }
        refreshRoomMetadata()
    }

    /**
     * Handles the event when a participant disconnects from the call, with special handling for one-on-one calls.
     */
    private fun onParticipantDisconnected() {
        if (getCurrentCallType() == CallType.ONE_ON_ONE.type && LCallActivity.isInCalling()) {
            timeoutCheckState = TimeoutCheckState.PARTICIPANT_LEAVE
            roomId?.let { rid ->
                LCallManager.checkCallWithTimeout(LCallManager.CallState.LEAVE_CALL, LCallManager.DEF_LEAVE_CALL_TIMEOUT, rid) { status ->
                    if (status) sendTimeoutBroadcast(rid)
                }
            }
        }
    }

    /**
     * Initiates a timeout check for 1-on-1 call scenarios where the call hasn't been answered yet.
     */
    private fun start1V1CallTimeout(rid: String) {
        roomCtl.updateCallStatus(if (callRole == CallRole.CALLER) CallStatus.CALLING else CallStatus.JOINING)
        timeoutCheckState = TimeoutCheckState.ONGOING_CALL
        LCallManager.checkCallWithTimeout(LCallManager.CallState.ONGOING_CALL, LCallManager.DEF_ONGOING_CALL_TIMEOUT, rid) { sendTimeoutBroadcast(rid) }
    }

    /**
     * Cancels any active call timeout checks that may be running.
     */
    private fun cancelCallTimeoutCheck() {
        if (timeoutCheckState != TimeoutCheckState.NONE) {
            roomId?.let { if (it.isNotEmpty()) LCallManager.cancelCallWithTimeout(it) }
            timeoutCheckState = TimeoutCheckState.NONE
        }
    }

    /**
     * Sends a broadcast notification when a call timeout occurs.
     */
    private fun sendTimeoutBroadcast(roomId: String) {
        val intent = Intent(LCallConstants.CALL_ONGOING_TIMEOUT).apply {
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
        ApplicationHelper.instance.sendBroadcast(intent)
    }

    /**
     * Determines whether barrage (floating comments/messages) should be shown for a remote participant.
     * */
    private fun shouldShowBarrageForRemoteParticipant(participant: Participant): Boolean {
        val participantUid = LCallManager.getUidByIdentity(participant.identity?.value)
        return participantUid != null && participantUid != mySelfId
    }

    /**
     * Handles the event when a participant's track is muted, displaying appropriate UI messages and updating participant ordering.
     */
    private fun onTrackMuted(event: RoomEvent.TrackMuted) {
        if (event.publication.source == Track.Source.MICROPHONE && event.publication.muted && event.publication.subscribed && event.participant is RemoteParticipant) {
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                showCallBarrageMessage(event.participant, getString(R.string.call_barrage_message_close_mic))
            }
        }
        if (event.participant is RemoteParticipant && event.publication.source == Track.Source.CAMERA && event.publication.muted) participantManager.resortParticipants()
    }

    /**
     * Handles the event when a participant's track is unmuted, displaying appropriate UI messages and updating participant ordering.
     */
    private fun onTrackUnmuted(event: RoomEvent.TrackUnmuted) {
        if (event.publication.source == Track.Source.MICROPHONE && event.participant is RemoteParticipant) {
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                showCallBarrageMessage(event.participant, getString(R.string.call_barrage_message_open_mic))
            }
        }
        if (event.participant is RemoteParticipant && event.publication.source == Track.Source.CAMERA) participantManager.resortParticipants()
    }

    /**
     * Checks and handles screen sharing state changes for remote participants in a call.
     */
    private fun checkRemoteUserScreenShare(participant: Participant) {
        if (participant is RemoteParticipant) {
            val isSharing = participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null
            if (isSharing && !callUiController.isShareScreening.value) {
                L.i { "[Call] CallViewModel ${participant.identity?.value} start screen sharing." }
                participantManager.resortParticipants(); participantManager.setScreenSharingUser(participant); callUiController.setShareScreening(true)
                showCallBarrageMessage(participant, getString(R.string.call_barrage_message_screensharing))
            }
            if (participantManager.screenSharingUser.value?.identity?.value == participant.identity?.value && !isSharing) {
                L.i { "[Call] CallViewModel ${participant.identity?.value} stop screen sharing." }
                participantManager.resortParticipants(); callUiController.setShareScreening(false); participantManager.setScreenSharingUser(null)
            }
        }
    }

    /**
     * Handles connection quality changes for local participant in a call, displaying appropriate error messages when quality degrades.
     */
    private fun onConnectionQualityChanged(participant: Participant, quality: ConnectionQuality) {
        L.i { "[Call] LCallViewModel ConnectionQualityChanged ${participant.identity?.value} quality = ${quality.name}." }
        if (participant is LocalParticipant) {
            val isPoorNow = quality !in goodQualities
            val now = SystemClock.elapsedRealtime()

            if (isPoorNow) {
                val shouldNotify = (now - lastLocalPoorErrorTime > networkPoorInterval)
                if (shouldNotify) {
                    roomCtl.collectError(
                        NetworkConnectionPoorException(getString(R.string.call_myself_network_poor_tip))
                    )
                    lastLocalPoorErrorTime = now
                }
                currentCallNetworkPoor = true
            } else {
                currentCallNetworkPoor = false
            }
        }
    }

    /**
     * Handles changes in active speakers during a call, with debounce logic to prevent frequent updates.
     */
    private fun onActiveSpeakersChanged(speakers: List<Participant>) {
        val filtered = speakers.filter { sp -> participantManager.participants.value.contains(sp) }
        if (lastSpeakers == filtered || (hasSpeaker && filtered.isEmpty())) return
        lastSpeakers = filtered
        debounceSpeakerUpdateJob?.cancel()
        debounceSpeakerUpdateJob = viewModelScope.launch(Dispatchers.Default) {
            val delayTime = if (filtered.isEmpty()) { hasSpeaker = false; 2500L } else { hasSpeaker = true; 200L }
            delay(delayTime)
            withContext(Dispatchers.IO) {
                participantManager.onActiveSpeakersChanged(filtered)
                participantManager.sortParticipantsBySpeaker(filtered)
                hasSpeaker = false
            }
        }
    }

    fun doExitClear() {
        onCleared()
        isCallResourceReleased = true
    }

    /**
     * Cleans up resources when the ViewModel is cleared.
     */
    override fun onCleared() {
        super.onCleared()
        L.i { "[Call] LCallViewModel onCleared start." }
        if (isCallResourceReleased) return
        shouldTriggerFeedbackView()
        roomCtl.disconnectAndRelease()
        try { audioHandler.stop(); audioHandler.audioDeviceChangeListener = null } catch (_: Exception) {}
        LCallManager.resetCallingTime()
        timerManager.stopCallTimer(); timerManager.stopCountdown()
        setConnectedServerUrl(null)
        resetFeedbackData()
        L.i { "[Call] LCallViewModel onCleared done." }
    }

    /**
     * Enables or disables the local participant's camera with proper error handling and permission checks.
     */
    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (room.localParticipant.videoTrackPublications.isEmpty()) {
                    if (roomCtl.roomMetadata.value?.canPublishVideo == true) {
                        if (enabled) withContext(Dispatchers.IO) {
                            room.localParticipant.setCameraEnabled(true)
                            roomCtl.updateCameraEnabled(true)
                        }
                    } else {
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                } else {
                    room.localParticipant.setCameraEnabled(enabled)
                    roomCtl.updateCameraEnabled(enabled)
                }
            } catch (e: Exception) {
                L.e { "[Call] LCallViewModel setCameraEnabled error = ${e.message}" }
            }
        }
    }

    /**
     * Retrieves a list of unique user IDs (UIDs) for all remote participants in the current call.
     */
    fun getCurrentCallUidList(): List<String> {
        return room.remoteParticipants.map { identityId ->
            var userId = identityId.key.value
            if (userId.contains(".")) {
                userId = userId.split(".")[0]
            }
            userId
        }
    }

    /**
     * Toggles the mute state of a participant's audio track via RTM (Real-Time Messaging).
     */
    fun toggleMute(participant: Participant) { rtm.toggleMute(participant) }

    /**
     * Flips the camera of the local participant between front and back positions.
     */
    fun flipCamera() {
        val vt = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
            ?: return
        val newPos = when (vt.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }
        vt.switchCamera(position = newPos)
    }

    /**
     * Calculates the remaining duration of a countdown timer in seconds.
     */
    private fun calculateCountDownDuration(expiredTimeMs: Long, currentTimeMs: Long): Long = if (expiredTimeMs < currentTimeMs) 0 else (expiredTimeMs - currentTimeMs) / 1000

    /**
     * Retrieves the current call type from the call manager.
     */
    private fun getCurrentCallType(): String { return LCallManager.getCallData(roomId)?.type ?: "" }

    /**
     * Creates and adds a new call data entry to the call manager with the provided call details.
     */
    private fun addStartCallData(forWhat: For, callerId: String, callName: String, roomId: String, callType: CallType, createdAt: Long) {
        val callData = CallData(
            type = callType.type,
            version = 0,
            createdAt = createdAt,
            roomId = roomId,
            caller = CallDataCaller(callerId, DEFAULT_DEVICE_ID),
            conversation = forWhat.id,
            null,
            callName = callName,
            source = CallDataSourceType.LOCAL
        )
        LCallManager.addCallData(callData)
    }

    /**
     * Sends a call initiation text message to participants or creates a local notification.
     */
    private fun sendStartCallTextMessage(forWhat: For, callType: CallType, systemShowTimestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val createCallMessageTime = System.currentTimeMillis()
            val mySelfName = LCallManager.getDisplayName(mySelfId)
            val textContent = if (callType == CallType.GROUP) ApplicationHelper.instance.getString(R.string.call_group_send_message, mySelfName) else ApplicationHelper.instance.getString(R.string.call_1v1_send_message)
            LCallManager.sendOrLocalCallTextMessage(CallActionType.START, textContent, DEFAULT_DEVICE_ID, createCallMessageTime, systemShowTimestamp, For.Account(mySelfId), forWhat, callType, callConfig.createCallMsg)
        }
    }

    /**
     * Sends a synchronization message to notify your other device that the local device has joined the call.
     */
    private fun sendJoinSyncControlMessage(forWhat: For, roomId: String, callerId: String) {
        viewModelScope.launch(Dispatchers.IO) { callToChatController.syncJoinedMessage(mySelfId, CallRole.CALLEE, callerId, CallRole.CALLEE.type, roomId, forWhat.id, null) }
    }

    /**
     * Clears the current error state in room control by setting it to null.
     */
    fun dismissError() { roomCtl.collectError(null) }

    /**
     * Sets the connected server URL for call engine.
     */
    fun setConnectedServerUrl(url: String?) {
        LCallEngine.setConnectedServerUrl(url)
    }

    /**
     * Registers a listener for server node selection events and handles the switching process if needed.
     */
    private fun registerListenSwitchCallServer() {
        viewModelScope.launch {
            LCallEngine.serverNodeSelected
                .map { it?.url }
                .distinctUntilChanged()
                .collect { url ->
                    if (url == null) return@collect
                    if (callStatus.value != CallStatus.CONNECTED) return@collect

                    L.i { "[call] registerListenSwitchCallServer serverNodeSelected: $url" }
                    roomCtl.updateCallStatus(CallStatus.SWITCHING_SERVER)
                    room.disconnect()

                    val body = StartCallRequestBody(
                        callIntent.callType,
                        LCallConstants.CALL_VERSION,
                        System.currentTimeMillis(),
                        conversation = callIntent.conversationId,
                        roomId = roomId
                    )
                    val joinCallParams = LCallManager.createStartCallParams(body)
                    connectToRoom(listOf(url), joinCallParams, LCallEngine.isUseQuicSignal())
                }
        }
    }

    private fun registerListenSwitchConnectionType() {
        viewModelScope.launch {
            combine(
                LCallEngine.connectionType,
                LCallEngine.serverNodeSelected
            ){ connectionType, serverNode ->
                connectionType to serverNode
            }.filter { (connectionType, _)->
                val useQuicSignal = connectionType == CONNECTION_TYPE.HTTP3_QUIC
                roomCtl.isUseQuicSignal() != useQuicSignal &&
                        callStatus.value == CallStatus.CONNECTED
            }.collect { (connectionType, serverNode) ->
                val useQuicSignal = connectionType == CONNECTION_TYPE.HTTP3_QUIC
                L.i { "[call] registerListenSwitchConnectionType connectionType: $connectionType" }
                // 更新状态并断开当前连接
                roomCtl.updateCallStatus(CallStatus.SWITCHING_SERVER)
                room.disconnect()
                // 构建请求参数
                val body = StartCallRequestBody(
                    callIntent.callType,
                    LCallConstants.CALL_VERSION,
                    System.currentTimeMillis(),
                    conversation = callIntent.conversationId,
                    roomId = roomId
                )
                val joinCallParams = LCallManager.createStartCallParams(body)
                // 选取 server URL
                val serverUrls = serverNode?.url
                    ?.let(::listOf)
                    ?: callIntent.callServerUrls
                // 重新连接
                connectToRoom(serverUrls, joinCallParams, useQuicSignal)
            }
        }
    }

    /**
     * Checks if the feedback view should be triggered and prepares the necessary call feedback data.
     */
    fun shouldTriggerFeedbackView() {
        if(CallFeedbackTriggerManager.shouldTriggerFeedback(currentCallNetworkPoor)) {
            val callInfo = FeedbackCallInfo(
                userIdentity = userIdentity ?: LCallManager.getMyIdentity(),
                userSid = userSid ?: "",
                roomId = getRoomId() ?: "",
                roomSid = roomSid ?: "",
            )
            LCallManager.setCallFeedbackInfo(callInfo)
        }
    }

    /**
     * Resets the feedback-related data to its default state.
     */
    private fun resetFeedbackData() {
        userSid = ""
        userIdentity = null
        roomSid = ""
        currentCallNetworkPoor = false
    }

    /**
     * Handles the sending of a critical alert notification to the server.
     */
    fun handleCriticalAlert(uid: String ?= null, gid: String ?= null, callback: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val chatHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application).httpClient()
            val auth = SecureSharedPrefsUtil.getBasicAuth()
            val request = CriticalAlertRequestBody(destination = uid, gid = gid)
            withContext(Dispatchers.IO) {
                chatHttpClient.httpService.sendCriticalAlert(auth, request)
                    .subscribe({
                        if(it.status == 0) {
                            callback?.invoke(true)
                            showCallBarrageMessage(
                                room.localParticipant,
                                getString(R.string.call_barrage_message_critical_alert_success)
                            )
                        }else {
                            L.e { "[Call] handleCriticalAlert failed, status = ${it.status} reason = ${it.reason}" }
                            callback?.invoke(false)
                            getString(R.string.call_barrage_message_critical_alert_failed)
                        }
                    }, {
                        it.printStackTrace()
                        val reason = it.message
                        val code = (it as? HttpException)?.code()
                        callback?.invoke(false)
                        when (code) {
                            413 -> {
                                L.w { "[Call] Critical alert limited - status: $code reason: $reason" }
                                showToastMessage(getString(R.string.call_barrage_message_critical_alert_limited))
                            }
                            else -> {
                                L.e { "[Call] Critical alert failed - status: $code, reason: $reason" }
                                showToastMessage(getString(R.string.call_barrage_message_critical_alert_failed))
                            }
                        }
                    })
            }
        }
    }

    /**
     * Displays a short toast message on the UI thread.
     */
    private fun showToastMessage(message: String) {
        viewModelScope.launch {
            try {
                ToastUtil.show(message)
            } catch (e: Exception) {
                L.e(e) { "[Call] Failed to show toast message: $message" }
            }
        }
    }

    /**
     * Checks the critical alert status for a specific conversation based on the provided call intent.
     */
    private fun checkCriticalAlertStatusById(callIntent: CallIntent) {
        if (callIntent.callType == CallType.GROUP.type) {
            callIntent.conversationId?.let { conversationId ->
                viewModelScope.launch(Dispatchers.IO) {
                    val group = callToChatController.getSingleGroupInfo(com.difft.android.base.utils.application, conversationId)
                    val status = group.orElse(null)?.criticalAlert ?: false
                    withContext(Dispatchers.Main) {
                        callUiController.setCriticalAlertEnable(status)
                    }
                }
            }
        }
    }

    fun is1v1ShowCriticalAlertEnable(callStatus: CallStatus): Boolean {
        return callType.value == CallType.ONE_ON_ONE.type && callRole == CallRole.CALLER && callStatus == CallStatus.CALLING
    }

    fun isGroupShowCriticalAlertEnable(isCriticalAlertEnable: Boolean): Boolean {
        return callType.value == CallType.GROUP.type && isCriticalAlertEnable
    }

    fun isRequestingPermission() = callUiController.isRequestingPermission.value

    fun isControlButtonClickEnabled(): Boolean {
        return if(callType.value == CallType.ONE_ON_ONE.type) {
            room.state == Room.State.CONNECTED
        } else {
            callStatus.value == CallStatus.CONNECTED || callStatus.value == CallStatus.RECONNECTED
        }
    }
}