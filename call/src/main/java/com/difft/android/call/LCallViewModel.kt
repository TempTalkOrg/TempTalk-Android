package com.difft.android.call

import android.app.Application
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallActivity.Companion.EXTRA_CONTROL_TYPE
import com.difft.android.call.LCallActivity.Companion.EXTRA_PARAM_ROOM_ID
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CancelHandRtmMessage
import com.difft.android.call.data.CountDownTimerData
import com.difft.android.call.data.EndCallRtmMessage
import com.difft.android.call.data.HandUpUserData
import com.difft.android.call.data.HandUpUserInfo
import com.difft.android.call.data.HandsUpData
import com.difft.android.call.data.RTM_MESSAGE_KEY_TEXT
import com.difft.android.call.data.RTM_MESSAGE_KEY_TOPIC
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_END_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_MUTE
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RAISE_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESUME_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RaiseHandRtmMessage
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.data.RtmDataPacket
import com.difft.android.call.data.RtmMessage
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.util.StringUtil
import com.difft.android.call.util.sortParticipantsByPriority
import difft.android.messageserialization.For
import com.difft.android.network.NetUtil
import com.difft.android.network.config.UserAgentManager
import com.google.gson.Gson
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.TTEncryptor
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.RoomException
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.Participant.Identity
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.util.flow
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asObservable
import kotlinx.coroutines.rx3.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.LivekitTemptalk
import livekit.org.webrtc.CameraXHelper
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor
import org.json.JSONObject
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException


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
    }

    private val callToChatController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callToChatController
    }

    private val messageEncryptor: INewMessageContentEncryptor by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).messageEncryptor
    }

    val audioHandler by lazy {
        AudioSwitchHandler(application).apply {
            loggingEnabled = BuildConfig.DEBUG
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
            ) + if(callIntent.callType == CallType.ONE_ON_ONE.type) {
                listOf(
                    AudioDevice.Earpiece::class.java,
                    AudioDevice.Speakerphone::class.java
                )
            } else {
                listOf(
                    AudioDevice.Speakerphone::class.java,
                    AudioDevice.Earpiece::class.java
                )
            }
        }
    }

    val room = LiveKit.create(
        appContext = application,
        options = getRoomOptions(),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = audioHandler,
                audioProcessorOptions = AudioProcessorOptions(capturePostProcessor = audioProcessor)
            )
        )
    )

    private var roomId: String? = null

    private var e2eeKey: ByteArray? = null

    private var _callRoomName: String = callIntent.roomName

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    private val countdownTopics = setOf(
        RTM_MESSAGE_TOPIC_SET_COUNTDOWN,
        RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN,
        RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN,
        RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
    )

    private val mutableHandsUpParticipants = MutableStateFlow<List<String>>(emptyList())

    private val mutableHandsUpUserInfo = MutableStateFlow<List<HandUpUserInfo>>(emptyList())
    val handsUpUserInfo = mutableHandsUpUserInfo.hide()

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    val participants = MutableStateFlow<List<Participant>>(emptyList())

    val ttCallResponse = room::ttCallResp.flow

    object ParticipantsLock

    private val _callType = MutableStateFlow(callIntent.callType)

    val callTypeStateFlow = _callType.asStateFlow()

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker = mutablePrimarySpeaker.asStateFlow()

    private val activeSpeakers = room::activeSpeakers.flow

    private val mutableRoomMetadata = MutableLiveData<RoomMetadata>(
        RoomMetadata(canPublishAudio = true, canPublishVideo = true)
    )

    val roomMetadata = mutableRoomMetadata.hide()

    private val mutableIsParticipantShareScreen = MutableStateFlow(false)
    val isParticipantSharedScreen = mutableIsParticipantShareScreen.hide()

    private val mWhoSharedScreen = MutableStateFlow<RemoteParticipant?>(null)
    val whoSharedScreen = mWhoSharedScreen.hide()

    private val mutableIsNoSpeakSoloTimeout = MutableStateFlow(false)
    val isNoSpeakSoloTimeout = mutableIsNoSpeakSoloTimeout.hide()

    // Controls
    private val mutableMicEnabled = MutableStateFlow(false)
    val micEnabled = mutableMicEnabled.asStateFlow()

    private val mutableShowUsersEnabled = MutableStateFlow(false)
    val showUsersEnabled = mutableShowUsersEnabled.hide()

    private val mutableCameraEnabled = MutableStateFlow(false)
    val cameraEnabled = mutableCameraEnabled.asStateFlow()

    private val mutableAudioDevice = MutableStateFlow(audioHandler.selectedAudioDevice)
    val currentAudioDevice = mutableAudioDevice.hide()

    private val mutableBarrageMessage = MutableStateFlow<BarrageMessage?>(null)
    val barrageMessage = mutableBarrageMessage.asStateFlow()

    private val mutableHandsUpEnabled = MutableStateFlow(false)
    val handsUpEnabled = mutableHandsUpEnabled.hide()

    private val mutableShowHandsUpEnabled = MutableStateFlow(false)
    val showHandsUpEnabled = mutableShowHandsUpEnabled.hide()

    private val mutableShowToolBarBottomViewEnable = MutableStateFlow(false)
    val showToolBarBottomViewEnable = mutableShowToolBarBottomViewEnable.hide()

    private val mutableShowBottomCallEndViewEnable = MutableStateFlow(false)
    val showBottomCallEndViewEnable = mutableShowBottomCallEndViewEnable.hide()

    private val mutableDeNoiseEnable = MutableStateFlow(true)
    val deNoiseEnable = mutableDeNoiseEnable.hide()

    private val _callDuration = MutableLiveData<Long>(0)// seconds
    var callDuration = MutableLiveData<String>("00:00")

    private var callDurationDisposable: Disposable? = null

    private var isRetryUrlConnecting = false

    private var isCallResourceReleased = false

    private enum class TimeoutCheckState { NONE, PARTICIPANT_LEAVE, ONGOING_CALL }
    private var timeoutCheckState = TimeoutCheckState.NONE

    private val mutableCallStatus = MutableStateFlow( if (callIntent.action == CallIntent.Action.START_CALL) CallStatus.CALLING else CallStatus.JOINING )
    val callStatus = mutableCallStatus.hide()

    private var countDownDurationDisposable: Disposable? = null

    private val _countDownDuration = MutableStateFlow<Long>(0) // seconds
    val countDownDuration = _countDownDuration.hide()

    private val _countDownEnabled = MutableStateFlow( false)
    val countDownEnabled = _countDownEnabled.hide()

    val showControlBarEnabled = MutableStateFlow( true)
    var speakerCountDownDurationStr = MutableLiveData("00:00")

    private var debounceSpeakerUpdateJob: Job? = null
    private var hasSpeaker: Boolean = false
    private var lastSpeakers: List<Participant>? = null
    private var noSpeakCheckDisposable: Disposable? = null
    private var isNoSpeakerChecking = false
    private var isOnePersonChecking = false


    private fun updateCallData(callType: String) {
        _callType.value = callType
    }

    fun getRoomId(): String? {
        return roomId
    }

    fun getE2eeKey(): ByteArray? {
        return e2eeKey
    }

    fun getCallRoomName(): String{
        val callType = getCurrentCallType()
        val participantNum = room.remoteParticipants.size + 1
        return formatCallRoomTitle(
            callType,
            _callRoomName,
            participantNum
        )
    }

    private fun formatCallRoomTitle(callType: String, title: String, count: Int): String {
        return if (callType == CallType.ONE_ON_ONE.type)
            title
        else
            "$title ($count)"
    }

    private fun getE2EEOptions(): E2EEOptions? {
        var e2eeOptions: E2EEOptions? = null
        try {
            if (e2eeEnable){
                e2eeOptions = E2EEOptions().apply {
                    ttEncryptor = object :TTEncryptor{
                        override fun decryptCallKey(
                            eKey: String,
                            eMKey: String
                        ): ByteArray? {
                            L.d { "[call] decryptCallKey invoke" }
                            return messageEncryptor.decryptCallKey(eKey, eMKey)
                        }
                    }
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            L.w { "[Call] CallViewModel Native library loading error: ${e.message}" }
        } catch (e: Exception) {
            L.w { "[Call] CallViewModel getE2EEOptions error:" + e.stackTraceToString() }
        }
        return e2eeOptions
    }

    private fun getRoomOptions(): RoomOptions {
        return RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = getE2EEOptions(),
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = true,
            ),
            videoTrackCaptureDefaults = LocalVideoTrackOptions(
                deviceId = "",
                position = CameraPosition.FRONT,
//                captureParams = VideoPreset169.H1080.capture,
                captureParams = VideoCaptureParameter(1280, 720, 30),
                isPortrait = true // Set portrait mode for vertical video capture orientation
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 20_000,
                dtx = true,
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoPreset169.H1080.encoding,
//                videoEncoding = VideoEncoding(3_000_000, 30),
                videoCodec = VideoCodec.VP8.codecName,
                scalabilityMode = "L3T3"
            )
        )
    }



    private fun updateParticipants(newParticipants: List<Participant>) {
        synchronized(ParticipantsLock) {
            participants.value = sortParticipantsByPriority(newParticipants)
        }
    }



    fun getCurrentCallUidList(): List<String> {
        return room.remoteParticipants.map { identityId ->
            var userId = identityId.key.value
            if (userId.contains(".")) {
                userId = userId.split(".")[0]
            }
            userId
        }
    }


    private fun sortParticipants() {
        viewModelScope.launch {
            try {
                val currentParticipants = participants.value
                val sortedParticipants = withContext(Dispatchers.Default) {
                    sortParticipantsByPriority(currentParticipants)
                }
                val currentSize = participants.value.size
                if(currentParticipants.size == currentSize){
                    updateParticipants(sortedParticipants)
                }
            } catch (e: Exception) {
                L.e { "[Call] CallViewModel sortParticipants error: ${e.message}" }
            }
        }
    }

    private fun handleCameraTrackChange() {
        if (getCurrentCallType() != CallType.ONE_ON_ONE.type) {
            viewModelScope.launch {
                try {
                    sortParticipants()
                } catch (e: Exception) {
                    L.e { "[Call] CallViewModel handleCameraTrackChange error: ${e.message}" }
                }
            }
        }
    }

    private fun startSpeakerCountDownDuration () {
        if(countDownDurationDisposable == null) {
            _countDownEnabled.value = true
            speakerCountDownDurationStr.value = formatCountDownDuration(_countDownDuration.value)
            countDownDurationDisposable = Observable.interval(1, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    val rawSpeakerCountDownDuration = _countDownDuration.value.minus(1)
                    rawSpeakerCountDownDuration.let { duration ->
                        when {
                            duration < 0 -> disposeCountDownDuration()
                            else -> {
                                _countDownDuration.value = duration
                                speakerCountDownDurationStr.postValue(formatCountDownDuration(duration))
                            }
                        }
                    }
                }
        }
    }

    private fun startCallDuration() {
        if(callDurationDisposable == null) {
            callDurationDisposable = Observable.interval(1, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    val rawCallDuration = _callDuration.value?.plus(1)
                    rawCallDuration?.let { duration ->
                        _callDuration.postValue(duration)
                        val showTimeString = formatCallDuration(duration)
                        callDuration.postValue(showTimeString)
                        roomId?.let { roomId ->
                            LCallManager.updateCallingTime(roomId, showTimeString)
                        }
                    }
                }
        }
    }

    private fun disposeCallDuration() {
        callDurationDisposable?.dispose()
        callDurationDisposable = null
    }

    private fun disposeCountDownDuration() {
        countDownDurationDisposable?.dispose()
        countDownDurationDisposable = null
    }

    init {
        L.d { "[Call] LCallViewModel init start." }
        // Collect any errors.
        registerErrorCollector()

        // Init camera provider.
        initCameraProvider(application)

        // Connect server room.
        connectToRoom(callIntent.callServerUrls)

        // Init audio device change listener.
        initAudioDeviceChangeListener(audioProcessor)

        // Handle start call response.
        handleStartCallResponse()

        // Handle room events.
        handleRoomEvents()

        // Handle hand up user.
        handleHandsUpParticipants(application)

        // participant change listener.
        registerParticipantChangeListener()

        // Collect any changes in contacts.
        registerContactsUpdateListener()

        // Handle any changes in speakers.
        registerSpeakerChangeListener()

        L.d { "[Call] LCallViewModel init end." }
    }

    private fun connectToRoom(urls: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (urls.isEmpty()) {
                L.e { "[Call] LCallViewModel connectToRoom urls size is 0" }
                mutableError.value =
                    StartCallException(getString(R.string.call_params_url_exception_tip))
                return@launch
            }
            for (url in urls) {
                try {
                    L.i { "[Call] LCallViewModel current network state, netType:${NetUtil.getNetWorkSumary()} isConnected:${NetUtil.checkNet(ApplicationHelper.instance)}" }
                    room.e2eeOptions = getE2EEOptions()
                    room.connect(
                        url = url,
                        token = "",
                        options = ConnectOptions().apply {
                            try {
                                this.userAgent = UserAgentManager.getUserAgent()
                                this.ttCallRequest = LivekitTemptalk.TTCallRequest
                                    .newBuilder()
                                    .setToken(callIntent.appToken)
                                    .setStartCall(LivekitTemptalk.TTStartCall.parseFrom(callIntent.startCallParams))
                                    .build()
                            }catch (e: Exception){
                                mutableError.value = StartCallException(e.message)
                                return@launch
                            }
                        }
                    )

                    L.i { "[Call] LCallViewModel connectToRoom connected" }
                    isRetryUrlConnecting = false
                    mutableAudioDevice.value = audioHandler.selectedAudioDevice
                    return@launch
                }
                catch (e: Throwable) {
                    when (e) {
                        is SocketTimeoutException, is SSLHandshakeException, is UnknownHostException -> {
                            L.e { "[Call] LCallViewModel connectToRoom timeout, url:$url error: ${e.message}" }
                            room.disconnect()
                            if(url == urls.lastOrNull()){
                                isRetryUrlConnecting = false
                                mutableError.value = e
                            }else{
                                isRetryUrlConnecting = true
                            }
                        }
                        is RoomException.NoAuthException, is RoomException.StartCallException ->{
                            mutableError.value = StartCallException(e.message)
                            break
                        }
                        else -> {
                            L.e { "[Call] LCallViewModel connectToRoom error, url:$url error: ${e.message}" }
                            mutableError.value = e
                            break
                        }
                    }
                }
            }
        }


    }


    private fun handlePrimarySpeaker(activeSpeakers: List<Participant> = emptyList()) {
        mutablePrimarySpeaker.value = when {
            activeSpeakers.isNotEmpty() -> activeSpeakers.maxByOrNull { it.audioLevel }
            isParticipantSharedScreen.value && whoSharedScreen.value != null -> whoSharedScreen.value
            else -> null
        }
    }


    private fun checkRemoteUserScreenShare(participant: Participant){
        if(participant is RemoteParticipant){
            if(isParticipantScreenSharing(participant) && !isParticipantSharedScreen.value){
                L.d { "[call] LCallViewModel checkRemoteUserScreenShare set true:${participant.identity}" }
                sortParticipants()
                mWhoSharedScreen.value = participant
                mutableIsParticipantShareScreen.value = true
                showCallBarrageMessage(participant, getString(R.string.call_barrage_message_screensharing))
            }
            if(whoSharedScreen.value?.identity?.value == participant.identity?.value && !isParticipantScreenSharing(participant)){
                L.d { "[call] LCallViewModel checkRemoteUserScreenShare set false:${participant.identity}" }
                sortParticipants()
                mutableIsParticipantShareScreen.value = false
                mWhoSharedScreen.value = null
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        L.i { "[call] LCallViewModel do onCleared start." }
        // Make sure to release any resources associated with LiveKit
        if(isCallResourceReleased){
            L.i { "[call] LCallViewModel isCallResourceReleased." }
            return
        }
        // Disconnect from the room and release resources.
        try {
            room.disconnect()
            room.release()
        }catch (e: Exception){
            L.e { "[call] LCallViewModel onCleared error:${e.message}" }
        }

        // Stop the camera
        cameraProvider?.let { CameraCapturerUtils.unregisterCameraProvider(it) }

        // Stop audio handler
        try {
            audioHandler?.let {
                it.stop()
                it.audioDeviceChangeListener = null
            }
        }catch (e: Exception){
            L.e { "[call] LCallViewModel audioHandler release error:${e.message}" }
        }

        LCallManager.resetCallingTime()
        disposeNoSpeakCheck()
        disposeCallDuration()
        disposeCountDownDuration()

        // release e2eeOption object
        room.e2eeOptions?.ttEncryptor = null
        room.e2eeOptions = null

        L.i { "[call] LCallViewModel do onCleared done." }
    }

    fun setShowUserEnabled(enabled: Boolean){
        mutableShowUsersEnabled.tryEmit(enabled)
    }

    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false) {
        viewModelScope.launch {
            try {
                if(room.localParticipant.audioTrackPublications.isEmpty()){
                    if(roomMetadata.value?.canPublishAudio == true){
                        setMicrophoneAndUpdateUI(enabled, publishMuted)
                    }else{
                        L.d { "[call] LCallViewModel audio stream is limit" }
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                }else{
                    setMicrophoneAndUpdateUI(enabled, publishMuted)
                }
            } catch (e: Exception){
                L.e { "[call] LCallViewModel setMicEnabled error:${e.message}" }
            }
        }
    }

    private fun setMicrophoneAndUpdateUI(micEnable: Boolean, publishMuted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                room.localParticipant.setMicrophoneEnabled(micEnable, publishMuted)
                mutableMicEnabled.value = micEnable && !publishMuted
            }catch (e: Throwable){
                L.e { "[call] LCallViewModel setMicrophoneAndUpdateUI error:${e.message}" }
            }
        }
    }

    fun setCurrentAudioDevice(device: AudioDevice){
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioHandler.selectDevice(device)
                mutableAudioDevice.value = device
            }catch (e: Exception){
                L.e { "[call] LCallViewModel setCurrentAudioDevice error:${e.message}" }
            }
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if(room.localParticipant.videoTrackPublications.isEmpty()){
                    if(roomMetadata.value?.canPublishVideo == true){
                        if(enabled){
                            withContext(Dispatchers.IO){
                                room.localParticipant.setCameraEnabled(true)
                                mutableCameraEnabled.value = true
                            }
                        }
                    }else{
                        L.d { "[call] LCallViewModel video stream is limit" }
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                }else{
                    room.localParticipant.setCameraEnabled(enabled)
                    mutableCameraEnabled.value = enabled

                }
            } catch (e: Exception){
                L.e { "[call] LCallViewModel setCameraEnabled error:${e.message}" }
            }
        }
    }

    fun setDeNoiseEnable(enabled: Boolean) {
        L.i { "[Call] LCallViewModel setDeNoiseEnable enabled:${enabled}" }
        mutableDeNoiseEnable.value = enabled
    }

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode = _isInPipMode.asStateFlow()

    fun setPipModeEnabled(enabled: Boolean) {
        _isInPipMode.value = enabled
    }

    fun isInPipMode(): Boolean {
        return _isInPipMode.value
    }

    fun getCallDuration(): Long {
        return _callDuration.value ?: 0
    }

    fun flipCamera() {
        val videoTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: return

        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }

        videoTrack.switchCamera(position = newPosition)
    }

    fun dismissError() {
        mutableError.value = null
    }


    fun doExitClear( ) {
        L.d { "[call] LCallViewModel doExitClear" }
        onCleared()
        isCallResourceReleased = true
    }

    fun reconnect() {
        L.e { "[call] LocalViewModel Reconnecting." }
        mutablePrimarySpeaker.value = null
        room.disconnect()
        connectToRoom(callIntent.callServerUrls)
    }

    private fun formatCallDuration(diffInSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(diffInSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(diffInSeconds) % 60
        val seconds = diffInSeconds % 60

        var formattedTime =
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        if (hours <= 0) {
            formattedTime = String.format("%02d:%02d", minutes, seconds)
        }

        callDuration.postValue(formattedTime)
        return formattedTime
    }

    private fun formatCountDownDuration(diffInSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(diffInSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(diffInSeconds) % 60
        val seconds = diffInSeconds % 60

        var formattedTime =
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        if (hours <= 0) {
            formattedTime = String.format("%02d:%02d", minutes, seconds)
        }
        return formattedTime
    }

    private fun getCurrentCallType(): String {
        return LCallManager.getCallData(roomId)?.type ?: ""
    }

    fun resetNoBodySpeakCheck() {
        disposeNoSpeakCheck()
        isNoSpeakerChecking = false
        isOnePersonChecking = false
        mutableIsNoSpeakSoloTimeout.value = false

        val currentParticipants = listOf<Participant>(room.localParticipant) + room.remoteParticipants.values.toList()
        checkNoSpeakOrOnePersonTimeout(currentParticipants, activeSpeakers.value, room)
    }

    private fun disposeNoSpeakCheck() {
        noSpeakCheckDisposable?.dispose()
        noSpeakCheckDisposable = null
    }

    /**
     * Initiates a check for periods of no speech.
     */
    private fun startNoSpeakCheck(timeout: Long, onTimeout: () -> Unit) {
        disposeNoSpeakCheck()
        noSpeakCheckDisposable = Completable.timer(timeout, TimeUnit.MILLISECONDS)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                L.d { "[call] LCallViewModel noSpeakCheck onTimeout." }
                onTimeout()
            }, { it.printStackTrace() })
    }

    /**
     * Checks for a timeout condition where no one is speaking or only one person is speaking for an extended period.
     */
    private fun checkNoSpeakOrOnePersonTimeout(participantsList: List<Participant>, speakers: List<Participant>, room: Room?) {
        callConfig?.let { config ->
            L.d { "[call] LCallViewModel checkNoBodySpeakTimeout config:$config" }
            val hasRemoteParticipants = room?.remoteParticipants?.isNotEmpty() ?: false
            val isSilent = participantsList.firstOrNull { it.isMicrophoneEnabled } == null || speakers.isEmpty()

            if (hasRemoteParticipants) {
                if (isSilent) {
                    if (isOnePersonChecking) {
                        // 如果之前是为单人检查，则取消并重置状态
                        disposeNoSpeakCheck()
                        isOnePersonChecking = false
                        mutableIsNoSpeakSoloTimeout.value = false
                    }
                    if (!isNoSpeakerChecking) {
                        L.d { "[call] LCallViewModel start multi Person No Speak timeout Checking." }
                        isNoSpeakerChecking = true
                        isOnePersonChecking = false
                        if(isTimeoutCheckApplicable()){
                            startNoSpeakCheck(config.autoLeave.promptReminder.silenceTimeout) {
                                mutableIsNoSpeakSoloTimeout.value = true
                                L.i { "[call] LCallViewModel multi Person No Speak timeout!." }
                            }
                        }
                    }
                } else {
                    // 有人说话，取消定时器
                    L.d { "[call] LCallViewModel have speaker disposeNoSpeakCheck." }
                    disposeNoSpeakCheck()
                    isNoSpeakerChecking = false
                    isOnePersonChecking = false
                    mutableIsNoSpeakSoloTimeout.value = false
                }
            } else {
                if (!isOnePersonChecking) {
                    L.d { "[call] LCallViewModel start One Person timeout Checking." }
                    isOnePersonChecking = true
                    isNoSpeakerChecking = false
                    if(isTimeoutCheckApplicable()){
                        startNoSpeakCheck(config.autoLeave.promptReminder.soloMemberTimeout) {
                            mutableIsNoSpeakSoloTimeout.value = true
                            L.i { "[call] LCallViewModel One Person timeout!." }
                        }
                    }
                }
            }
        }
    }

    /**
     * Determines whether a timeout check is applicable based on the current calling state.
     */
    private fun isTimeoutCheckApplicable() =
        LCallActivity.isInCalling() && !LCallActivity.isInCallEnding()

    /**
     * Displays a barrage message related to a call participant.
     */
    private fun showCallBarrageMessage(participant: Participant, message: String) {
        participant.identity?.value?.let { identityValue ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val name = LCallManager.getDisplayNameById(identityValue)?: identityValue
                    val showName = StringUtil.getShowUserName(name, 14)
                    withContext(Dispatchers.Main){
                        val barrageMessage = BarrageMessage(showName, message, System.currentTimeMillis())
                        mutableBarrageMessage.value = barrageMessage
                        L.d { "[call] LCallViewModel sendBarrageMessage barrageMessage:$barrageMessage" }
                    }
                } catch (e: Exception) {
                    // 处理异常，例如记录日志或显示错误消息
                    L.e { "[call] LCallViewModel sendBarrageMessage Error:$e" }
                }
            }
        }
    }

    /**
     * Sends barrage (or chat message) data to a specific topic.
     */
    fun sendBarrageData(message: String, topicType: String) {
        if(message.isNotEmpty()){
            val jsonData = JSONObject().put(RTM_MESSAGE_KEY_TEXT, message).put(RTM_MESSAGE_KEY_TOPIC, topicType)
            sendRtmMessage(true, jsonData.toString(), topicType)
            showCallBarrageMessage(room.localParticipant, message)
        }
    }

    /**
     * Sends an RTM (Real-Time Messaging) message with optional encryption and specific topic type.
     */
    private fun sendRtmMessage(encryptFlag: Boolean, message: String, topicType: String, identities: List<Identity>? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = if(encryptFlag) encryptRtmMessage(message) else message
            if(data == null) return@launch
            try {
                room.localParticipant.publishData(
                    data = data.toByteArray(Charsets.UTF_8),
                    identities = identities,
                    topic = topicType,
                )
            }catch (e: Exception){
                L.e { "[Call] LCallViewModel sendRtmMessage Failed to publish data: ${e.message}" }
            }
        }
    }

    /**
     * Encrypts an RTM (Real-Time Messaging) message using a predefined encryption algorithm.
     */
    private fun encryptRtmMessage(message: String): String? {
        val localPrivateKey = callToChatController.getLocalPrivateKey() ?: return null
        val rtmEncryptedMessage = try {
            messageEncryptor.encryptRtmMessage(message.toByteArray(), localPrivateKey, e2eeKey ?: throw IllegalArgumentException("E2EE key not found"))
        } catch (e: Exception) {
            L.e { "[Call] LCallViewModel sendRtmMessage Failed to encrypt message: ${e.message}" }
            return null
        }
        return rtmEncryptedMessage
    }

    /**
     * Toggles the mute status of a participant.
     */
    fun toggleMute(participant: Participant) {
        participant.identity?.value ?: return
        if(participant is LocalParticipant){
            setMicEnabled(false)
        }else {
            participant.identity?.let { identity ->
                val identities = listOf(identity)
                val rtmMessage = RtmMessage(
                    topic = RTM_MESSAGE_TOPIC_MUTE,
                    identities = identities,
                    sendTimestamp = System.currentTimeMillis()
                )
                val json = Json.encodeToString(rtmMessage)
                sendRtmMessage(true, json, RTM_MESSAGE_TOPIC_MUTE, identities)
            }
        }
    }

    /**
     * Sends a broadcast message indicating that a timeout event has occurred in a specific room.
     */
    private fun sendTimeoutBroadcast(roomId: String) {
        val intent = Intent(LCallConstants.CALL_ONGOING_TIMEOUT).apply {
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
        ApplicationHelper.instance.sendBroadcast(intent)
    }

    /**
     * Sends a broadcast message indicating that a hang-up event has occurred in a specific room.
     */
    private fun sendHangUpBroadcast(roomId: String) {
        L.i { "[Call] LCallViewModel sendHangUpBroadcast" }
        val intent = Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
            putExtra(EXTRA_CONTROL_TYPE, CallActionType.HANGUP.type)
            putExtra(EXTRA_PARAM_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
        ApplicationHelper.instance.sendBroadcast(intent)
    }

    /**
     * Cancels any ongoing timeout check for a call.
     */
    private fun cancelCallTimeoutCheck() {
        if(timeoutCheckState!= TimeoutCheckState.NONE) {
            L.i { "[Call] LCallViewModel Canceling ongoing call timeout detection" }
            roomId?.let { roomId ->
                if (roomId.isNotEmpty()) {
                    LCallManager.cancelCallWithTimeout(roomId)
                }
            }
            timeoutCheckState = TimeoutCheckState.NONE
        }
    }

    /**
     * Sends an RTM (Real-Time Messaging) message to a participant to continue a call.
     */
    fun sendContinueCallRtmMessage( participant: Participant) {
        L.i { "[Call] LCallViewModel sendContinueCallRtmMessage" }
        viewModelScope.launch {
            participant.identity?.let { identity ->
                val identities = listOf(identity)
                val rtmMessage = RtmMessage(
                    topic = RTM_MESSAGE_TOPIC_RESUME_CALL,
                    identities = identities,
                )
                val json = Json.encodeToString(rtmMessage)
                sendRtmMessage(true, json, RTM_MESSAGE_TOPIC_RESUME_CALL, identities)
            }
        }
    }

    /**
     * Checks whether the local participant's identity is present in the identities list of an RTM message.
     */
    private fun isLocalIdentityIInRtmIdentities(rtmMessage: RtmMessage): Boolean {
        return rtmMessage.identities?.map { it.value }
            ?.any { it.contains(room.localParticipant.identity!!.value) } ?: false
    }

    /**
     * Parses a byte array containing an RTM (Real-Time Messaging) data packet and converts it into an [RtmDataPacket] object.
     */
    private fun parseRtmDataPacket(data: ByteArray): RtmDataPacket? {
        return try {
            Gson().fromJson(String(data, Charsets.UTF_8), RtmDataPacket::class.java)
        } catch (e: Exception) {
            L.w { "[Call] LCallViewModel parsePlaintextRtmMessage Failed to decode JSON data" }
            null
        }
    }

    /**
     * Parses a string payload containing countdown timer data and converts it into a [CountDownTimerData] object.
     */
    private fun parseCountDownTimerData(payload: String): CountDownTimerData? {
        return try {
            Gson().fromJson(payload, CountDownTimerData::class.java)
        } catch (e: Exception) {
            L.w { "[Call] LCallViewModel parseCountDownTimerData Failed to decode JSON data" }
            null
        }
    }

    /**
     * Decrypts a real-time message received from a participant and returns the decrypted message object.
     */
    private fun rtmDecryptedMessage(participant: Participant, data: ByteArray): RtmMessage? {
        val uid = participant.identity?.value ?: return null
        val hisPublicKey = callToChatController.getTheirPublicKey(uid) ?: return null
        val rtmDecryptedMessage = try {
            val plainTextByteArray = messageEncryptor.decryptRtmMessage(data, hisPublicKey, e2eeKey ?: throw IllegalArgumentException("E2EE key not found"))
            Json.decodeFromString<RtmMessage>(String(plainTextByteArray, Charsets.UTF_8))
        } catch (e: Exception) {
            L.e { "[Call] LCallViewModel DataReceived Failed to parse RTM message: ${e.message}" }
            return null
        }
        return rtmDecryptedMessage
    }

    /**
     * Handles raw data received for a countdown timer event and processes it into a [CountDownTimerData] object.
     */
    private fun handleCountDownTimerData(data: ByteArray, handler: (CountDownTimerData) -> Unit) {
        parseRtmDataPacket(data)?.let { plaintext ->
            parseCountDownTimerData(plaintext.payload)?.let(handler)
        }
    }

    /**
     * Handles the connected state of the client within a communication session or room.
     */
    private fun handleConnectedState() {
        mutableCallStatus.value = CallStatus.CONNECTED
        startCallDuration()
    }

    /**
     * Calculates the remaining duration for a countdown timer based on the expired and current time.
     */
    private fun calculateCountDownDuration(expiredTimeMs: Long, currentTimeMs: Long): Long =
        if (expiredTimeMs < currentTimeMs) 0L else (expiredTimeMs - currentTimeMs) / 1000L

    /**
     * Handles data received from an encrypted event in a room.
     */
    private fun handleEncryptedDataReceived(event: RoomEvent.DataReceived) {
        event.participant?.let { participant ->
            viewModelScope.launch {
                val rtmMessage = withContext(Dispatchers.IO) {
                    rtmDecryptedMessage(participant, event.data)
                }
                event.topic?.let { topic ->
                    handleDecryptedMessage(topic, participant, rtmMessage)
                }
            }
        }
    }

    /**
     * Handles a decrypted real-time message received from a participant for a specific topic.
     */
    private fun handleDecryptedMessage(topic: String, participant: Participant, rtmMessage: RtmMessage?) {
        when (topic) {
            RTM_MESSAGE_TOPIC_CHAT -> {
                rtmMessage?.text?.let { text ->
                    // display barrage message
                    showCallBarrageMessage(participant, text)
                }
            }

            RTM_MESSAGE_TOPIC_MUTE -> {
                rtmMessage?.let {
                    if (isLocalIdentityIInRtmIdentities(it)) {
                        // mute local participant's mic
                        setMicEnabled(false)
                    }
                }
            }

            RTM_MESSAGE_TOPIC_RESUME_CALL-> {
                rtmMessage?.let {
                    if(isLocalIdentityIInRtmIdentities(rtmMessage)){
                        if(getCurrentCallType() == CallType.ONE_ON_ONE.type){
                            // resume nobody speak check
                            resetNoBodySpeakCheck()
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles data received from a hang-up event in a room.
     */
    private fun handleHangUpData(event: RoomEvent.DataReceived) {
        event.participant?.let { participant ->
            viewModelScope.launch {
                val rtmMessage = withContext(Dispatchers.IO) {
                    rtmDecryptedMessage(participant, event.data)
                }
                if((event.topic == rtmMessage?.topic) && event.topic == RTM_MESSAGE_TOPIC_END_CALL){
                    L.i { "[Call] LCallViewModel handleHangUpData receive end call message" }
                    roomId?.let { roomId ->
                        LCallManager.removeCallData(roomId)
                        sendHangUpBroadcast(roomId)
                    }
                }
            }
        }
    }

    /**
     * Handles data received from a countdown timer event in a room.
     */
    private fun handleCountDownData(event: RoomEvent.DataReceived) {
        event.data?.let { data ->
            handleCountDownTimerData(data) { countDownTimerData ->
                when (event.topic) {
                    RTM_MESSAGE_TOPIC_SET_COUNTDOWN, RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN -> {
                        handleCountDownSetOrRestart(countDownTimerData)
                    }
                    RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN -> {
                        handleCountDownExtend(countDownTimerData)
                    }
                    RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> {
                        handleCountDownClear()
                    }
                }
            }
        }
    }

    /**
     * Handles setting or restarting a countdown timer with the provided data.
     */
    private fun handleCountDownSetOrRestart(countDownTimerData: CountDownTimerData) {
        _countDownDuration.value = calculateCountDownDuration(countDownTimerData.expiredTimeMs, countDownTimerData.currentTimeMs)
        val operatorIdentity = countDownTimerData.operatorIdentity
        if(!TextUtils.isEmpty(operatorIdentity)){
            room.remoteParticipants[Identity(operatorIdentity)]?.let {
                showCallBarrageMessage(it, getString(R.string.call_barrage_message_countdown_timer))
            }
            startSpeakerCountDownDuration()
        } else {
            L.w { "[Call] LCallViewModel handleCountDownSetOrRestart Operator identity is empty" }
        }
    }

    /**
     * Handle the logic of extending the countdown.
     */
    private fun handleCountDownExtend(countDownTimerData: CountDownTimerData) {
        _countDownDuration.value = calculateCountDownDuration(countDownTimerData.expiredTimeMs, countDownTimerData.currentTimeMs)
    }

    /**
     * Handle the logic of clearing the countdown.
     */
    private fun handleCountDownClear() {
        _countDownEnabled.value = false
        _countDownDuration.value = 0L
        disposeCountDownDuration()
    }

    /**
     * Handles data received from a "raise hand" event in a room.
     */
    private fun handleHandsUpData(event: RoomEvent.DataReceived) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                event.data?.let { data ->
                    L.i { "[Call] LCallViewModel handleHandsUpData data:${String(data)}" }
                    parseRtmDataPacket(data)?.payload?.let { payload ->
                        val handsUpData = Gson().fromJson(payload, HandsUpData::class.java)
                        if (handsUpData != null) {
                            // handle the logic for raising or cancelling hands up
                            when (event.topic) {
                                RTM_MESSAGE_TOPIC_RAISE_HANDS_UP -> {
                                    // handle raise hands up logic here
                                    updateHandsUpParticipants(handsUpData.hands)
                                }
                                RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP -> {
                                    // handle cancel hands up logic here
                                    updateHandsUpParticipants(handsUpData.hands)
                                    if (mutableHandsUpEnabled.value && room.localParticipant.identity?.value !in getSortedHandsUpIdentities(handsUpData.hands)) {
                                        mutableHandsUpEnabled.value = false
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception){
                L.e { "[Call] Error handling hands up data: ${e.message}" }
            }
        }
    }

    /**
     * Sends a real-time message indicating that a participant has raised their hand for a specific topic.
     */
    private fun sendHandsUpRtmMessage(topic: String, participant: Participant) {
        L.i { "[Call] LCallViewModel sendContinueCallRtmMessage" }
        viewModelScope.launch(Dispatchers.IO) {
            participant.identity?.let { identity ->
                val identities = listOf(identity.value)
                val json = if (topic == RTM_MESSAGE_TOPIC_RAISE_HANDS_UP)
                    Gson().toJson(
                        RtmDataPacket(
                            payload = Gson().toJson( RaiseHandRtmMessage(topic = topic)),
                            signature = "",
                            sendTimestamp = System.currentTimeMillis(),
                            uuid = UUID.randomUUID().toString()
                        )
                    )
                else
                    Gson().toJson(
                        RtmDataPacket(
                            payload = Gson().toJson( CancelHandRtmMessage(topic = topic, hands = identities)),
                            signature = "",
                            sendTimestamp = System.currentTimeMillis(),
                            uuid = UUID.randomUUID().toString()
                        )
                    )
                sendRtmMessage(false, json, topic, emptyList())
            }
        }
    }

    /**
     * Sends a real-time message indicating that the call or session should be hung up.
     */
    fun sendHangUpRtmMessage() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = Gson().toJson(
                EndCallRtmMessage(
                    topic = RTM_MESSAGE_TOPIC_END_CALL,
                    sendTimestamp = System.currentTimeMillis(),
                )
            )
            sendRtmMessage(true, json, RTM_MESSAGE_TOPIC_END_CALL, emptyList())
        }
    }

    /**
     * Sets whether the "raise hand" view at the bottom of the screen is enabled for display.
     */
    fun setShowHandsUpBottomViewEnabled(enabled: Boolean) {
        L.i { "[Call] ShowHandsUpView setShowHandsUpBottomViewEnabled enabled:${enabled}" }
        mutableShowHandsUpEnabled.value = enabled
    }

    /**
     * Sets whether the toolbar at the bottom of the view is enabled for display.
     */
    fun setShowToolBarBottomViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowToolBarBottomViewEnable enabled:${enabled}" }
        mutableShowToolBarBottomViewEnable.value = enabled
    }

    /**
     * Sets whether the bottom call end view is enabled for display.
     */
    fun setShowBottomCallEndViewEnable(enabled: Boolean) {
        L.i { "[Call] setShowBottomCallEndViewEnable enabled:${enabled}" }
        mutableShowBottomCallEndViewEnable.value = enabled
    }

    /**
     * Enables or disables the "raise hand" functionality for a specific participant.
     */
    fun setHandsUpEnable(enabled: Boolean, participant: Participant) {
        L.i { "[Call] setHandsUpEnable enabled:$enabled, participant:${participant.identity?.value}" }
        mutableHandsUpEnabled.value = enabled
        val topic = if(enabled) RTM_MESSAGE_TOPIC_RAISE_HANDS_UP else RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP
        sendHandsUpRtmMessage(topic, participant)
    }

    /**
     * Updates the list of participants who have raised their hands.
     */
    private fun updateHandsUpParticipants(hands: List<HandUpUserData>?) {
        val sortedHandsUpIdentities = hands?.sortedBy { it.ts }?.map { it.identity } ?: emptyList()
        if (sortedHandsUpIdentities.isNotEmpty()) {
            mutableHandsUpParticipants.value = sortedHandsUpIdentities
        } else {
            mutableHandsUpParticipants.value = emptyList()
        }
    }

    /**
     * Retrieves a sorted list of identities for users who have raised their hands.
     */
    private fun getSortedHandsUpIdentities(hands: List<HandUpUserData>?): List<String> {
        return hands?.sortedBy { it.ts }?.map { it.identity } ?: emptyList()
    }

    /**
     * Sorts a list of participants by their speaking activity.
     */
    private fun participantsSortBySpeaker(speakers: List<Participant>) {
        val participantsList = participants.value ?: return
        // 检查每个发言者，看看是否需要排序
        for (speaker in speakers) {
            val speakerIndex = participantsList.indexOf(speaker)
            if (speakerIndex == 0) continue // 第一个发言者不需要排序
            try {
                if (speakerIndex > 0) {
                    val previousParticipant = participantsList[speakerIndex - 1]
                    if (isParticipantInactive(previousParticipant)){
                        sortParticipants()
                        return
                    }

                    // 检查前面的参与者中是否有未开麦克风的
                    val previousParticipants = participantsList.subList(0, speakerIndex)
                    if (previousParticipants.isNotEmpty() && previousParticipants.any { isParticipantInactive(it) }) {
                        sortParticipants()
                        return
                    }
                }
            } catch (e: Exception){
                L.e { "Error sorting participants by speaker: ${e.message}" }
                return
            }
        }
    }

    /**
     * Checks whether the specified participant is currently inactive.
     */
    private fun isParticipantInactive(participant: Participant): Boolean {
        return participant !is LocalParticipant && !participant.isScreenShareEnabled && !participant.isCameraEnabled && !participant.isMicrophoneEnabled
    }

    /**
     * Checks whether the specified participant is currently sharing their screen.
     */
    private fun isParticipantScreenSharing(participant: Participant): Boolean {
        return participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null
    }

    /**
     * Sets whether the control bar is enabled for display.
     */
    fun setShowControlBarEnabled(enabled: Boolean) {
        showControlBarEnabled.value = enabled
    }

    /**
     * Switch the audio device.
     */
    fun switchToNextAudioDevice(audioSwitchHandler: AudioSwitchHandler) {
        if (audioSwitchHandler.availableAudioDevices.size > 1) {
            audioSwitchHandler.availableAudioDevices
                .firstOrNull { it != mutableAudioDevice.value }
                ?.let { setCurrentAudioDevice(it) }
        }
    }

    /**
     * Sends a text message to initiate a call.
     */
    private fun sendStartCallTextMessage(forWhat: For, callType: CallType, systemShowTimestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val createCallMessageTime = System.currentTimeMillis()
            val mySelfName = LCallManager.getDisplayName(mySelfId)
            val textContent = if( callType == CallType.GROUP ){ ApplicationHelper.instance.getString(R.string.call_group_send_message, mySelfName) } else ApplicationHelper.instance.getString(R.string.call_1v1_send_message)
            // Based on the global configuration, choose whether to send the call text message locally or generate the call text message locally.
            LCallManager.sendOrLocalCallTextMessage(CallActionType.START, textContent, DEFAULT_DEVICE_ID, createCallMessageTime,systemShowTimestamp, For.Account(mySelfId), forWhat, callType, callConfig.createCallMsg)
        }
    }

    /**
     * Adds data for initiating a call.
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
        L.d { "[Call] startCall, addCallData:$callData" }
        LCallManager.addCallData(callData)
    }

    /**
     * Sends a synchronization control message to indicate the intention to join a specific room.
     */
    private fun sendJoinSyncControlMessage(forWhat: For, roomId: String, callerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Sync joined control message
            callToChatController.syncJoinedMessage(mySelfId, CallRole.CALLEE, callerId, CallRole.CALLEE.type, roomId, forWhat.id, null)
        }
    }

    /**
     * Starts the ringtone associated with a specific call type.
     */
    private fun startCallRingTone(callType: CallType) {
        viewModelScope.launch(Dispatchers.Default) {
            val ringIntent = Intent().apply {
                putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callType.type)
                putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLER.type)
            }
            LCallManager.startRingTone(ringIntent)
        }
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
     * Handles the start call response received after initiating a call.
     */
    private fun handleStartCallResponse() {
        viewModelScope.launch(Dispatchers.IO) {
            // Add timeout detection
            val response = withTimeoutOrNull(15000L) {
                ttCallResponse.filterNotNull().first()
            }

            if(response == null){
                // Handle the abnormal situation of timeout and no call response returned
                mutableError.value = StartCallException("call response timeout")
                return@launch
            } else {
                if(response.body == null || response.base.status != 0 ) {
                    mutableError.value = StartCallException("call response exception, status:${response.base.status}")
                    return@launch
                }
                roomId = response.body.roomId
                LCallActivity.setCurrentRoomId(roomId)
                val callerId = callIntent.callerId
                val callType = CallType.fromString(callIntent.callType) ?: CallType.ONE_ON_ONE
                val forWhat = when (callType) {
                    CallType.GROUP -> {
                        For.Group(callIntent.conversationId!!)
                    }
                    CallType.ONE_ON_ONE -> For.Account(callIntent.conversationId!!)
                    else -> For.Account(callIntent.callerId)
                }

                if (e2eeEnable) {
                    if(!response.body.emk.isNullOrEmpty()) {
                        val mk = messageEncryptor.decryptCallKey(response.body.publicKey, response.body.emk)
                        if(mk != null){
                            e2eeKey = mk
                        }else {
                            mutableError.value = StartCallException("e2ee key is null")
                            return@launch
                        }
                    }else {
                        mutableError.value = StartCallException("e2ee is enabled but emk is null")
                        return@launch
                    }
                }

                if(callIntent.action == CallIntent.Action.START_CALL) {
                    // Initiate a call ring
                    startCallRingTone(callType)
                    // Create CallData and cache
                    addStartCallData(forWhat, callerId, callIntent.roomName, response.body.roomId, callType, response.body.createdAt)
                    // Send a start call text message
                    sendStartCallTextMessage(forWhat, callType, response.body.systemShowTimestamp)
                } else if (callIntent.action == CallIntent.Action.JOIN_CALL) {
                    response.body.roomId?.let { roomId ->
                        // Sync joined control message
                        sendJoinSyncControlMessage(forWhat, roomId, callerId)
                        // Close the incoming notification
                        callToChatController.cancelNotificationById(roomId.hashCode())
                    }
                }
            }
        }
    }

    /**
     * Initializes a listener for audio device changes, which will be used to monitor changes in audio output or input devices.
     */
    private fun initAudioDeviceChangeListener(audioProcessor: DenoisePluginAudioProcessor?) {
        viewModelScope.launch(Dispatchers.IO) {
            audioHandler.let { handler ->
                handler.audioDeviceChangeListener = object : AudioDeviceChangeListener {
                    override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) {
                        callConfig?.let {
                            val excludedNameRegex = it.denoise?.bluetooth?.excludedNameRegex
                            val deviceName = selectedAudioDevice?.name
                            if (!excludedNameRegex.isNullOrEmpty() && !deviceName.isNullOrEmpty() &&
                                Regex(excludedNameRegex, RegexOption.IGNORE_CASE).containsMatchIn(deviceName)) {
                                L.i { "[call] audioDevice is in excludedNameRegex: $excludedNameRegex" }
                                audioProcessor?.setEnabled(false)
                            } else {
                                audioProcessor?.setEnabled(deNoiseEnable.value)
                            }
                        }
                        mutableAudioDevice.value = selectedAudioDevice
                    }
                }
            }
        }
    }

    /**
     * Handles participants who have raised their hands within the context of the current ViewModel scope.
     */
    private fun handleHandsUpParticipants(context: Context) {
        viewModelScope.launch {
            mutableHandsUpParticipants.asObservable().collect {
                if (it.isNotEmpty()) {
                    val handUpUserInfoList = mutableListOf<HandUpUserInfo>()
                    it.forEach { id ->
                        val userName = LCallManager.getDisplayNameById(id) ?: id
                        val userAvatar = LCallManager.getAvatarByUid(context, id)
                            ?: LCallManager.createAvatarByNameOrUid(context, userName, id)
                        val handsUpInfo = HandUpUserInfo(userName = userName, userAvatar = userAvatar, userId = id)
                        handUpUserInfoList.add(handsUpInfo)
                    }
                    mutableHandsUpUserInfo.value = handUpUserInfoList
                } else {
                    if(mutableHandsUpUserInfo.value.isNotEmpty()){
                        mutableHandsUpUserInfo.value = emptyList()
                    }
                }
            }
        }
    }

    /**
     * Registers a listener for participant change within the ViewModel scope.
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
                updateParticipants(updatedParticipants)
            }
        }
    }

    /**
     * Registers a listener for contact updates within the ViewModel scope.
     */
    private fun registerContactsUpdateListener() {
        viewModelScope.launch(Dispatchers.IO) {
            callToChatController.getContactsUpdateListener().collect {
                LCallManager.updateCallContactorCache(it)
            }
        }
    }

    /**
     * Registers a listener for speaker changes within the ViewModel scope.
     */
    private fun registerSpeakerChangeListener() {
        viewModelScope.launch {
            combine(participants, activeSpeakers) { participants, speakers -> participants to speakers }
                .collect { (participantsList, speakers) ->
                    checkNoSpeakOrOnePersonTimeout(participantsList, speakers, room)
                }
        }
    }

    /**
     * Handler livekit sdk room event callback.
     */
    private fun handleRoomEvents() {
        viewModelScope.launch {
            room.events.collect {
                when (it) {
                    is RoomEvent.Disconnected -> {
                        L.e { "[Call] LCallViewModel RoomEvent.Disconnected error:${it.error} reason:${it.reason.name} room.state:${it.room.state}" }
                        if(it.reason != DisconnectReason.CLIENT_INITIATED) { // 客户端主动断开连接，不显示错误提示和结会逻辑
                            if (it.reason == DisconnectReason.RECONNECT_FAILED) {
                                mutableCallStatus.value = CallStatus.RECONNECT_FAILED
                            }
                            mutableError.value = DisconnectException(DisconnectException.ROOM_DISCONNECTED_MESSAGE, Exception(it.reason.name) )
                        }else {
                            mutableCallStatus.value = CallStatus.DISCONNECTED
                        }
                    }
                    is RoomEvent.FailedToConnect ->  {
                        L.i { "[Call] LCallViewModel FailedToConnect error:${it.error}" }
                        mutableCallStatus.value = CallStatus.CONNECTED_FAILED
                        mutableError.value = it.error
                    }
                    is RoomEvent.LocalTrackSubscribed -> {
                        L.i { "[Call] LCallViewModel LocalTrackSubscribed participant:${it.participant.identity}" }
                    }
                    is RoomEvent.DataReceived -> {
                        L.i { "[Call] DataReceived topic:${it.topic} identity:${it.participant?.identity}" }
                        when(it.topic){
                            RTM_MESSAGE_TOPIC_CHAT, RTM_MESSAGE_TOPIC_MUTE, RTM_MESSAGE_TOPIC_RESUME_CALL -> {
                                handleEncryptedDataReceived(it)
                            }
                            RTM_MESSAGE_TOPIC_RAISE_HANDS_UP, RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP -> {
                                handleHandsUpData(it)
                            }
                            RTM_MESSAGE_TOPIC_END_CALL -> {
                                handleHangUpData(it)
                            }
                            in countdownTopics -> {
                                handleCountDownData(it)
                            }
                            else -> { }
                        }
                    }
                    is RoomEvent.ParticipantDisconnected ->{
                        L.i { "[Call] LCallViewModel user leave room, participant:${it.participant.identity} room.remoteParticipants.size:${room.remoteParticipants.size}" }
                        if (getCurrentCallType() == CallType.ONE_ON_ONE.type && LCallActivity.isInCalling()){
                            L.i { "[call] LCallViewModel enable participant disconnected timeout detection" }
                            timeoutCheckState = TimeoutCheckState.PARTICIPANT_LEAVE
                            roomId?.let { roomId ->
                                LCallManager.checkCallWithTimeout(LCallManager.CallState.LEAVE_CALL, LCallManager.DEF_LEAVE_CALL_TIMEOUT, roomId,
                                    callBack = { status ->
                                        if(status) {
                                            L.i { "[call] LCallViewModel ParticipantDisconnected checkCallWithTimeout" }
                                            sendTimeoutBroadcast(roomId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    is RoomEvent.ParticipantConnected -> {
                        L.i { "[Call] LCallViewModel remote user join room, participant:${it.participant.identity} room.remoteParticipants.size:${room.remoteParticipants.size}" }
                        showCallBarrageMessage(it.participant, getString(R.string.call_barrage_message_join))
                        LCallManager.stopRingTone()
                        LCallManager.stopVibration()
                        cancelCallTimeoutCheck()
                        if (getCurrentCallType() == CallType.ONE_ON_ONE.type) {
                            handleConnectedState()
                            if(room.remoteParticipants.size>1){
                                // 1v1会议 变为instant call
                                L.i { "[Call] LCallViewModel callType 1v1 to instant" }
                                val callingListData = LCallManager.getCallListData()
                                roomId?.let { roomId ->
                                    if(roomId.isNotEmpty() && callingListData?.containsKey(roomId) == true){
                                        callingListData[roomId]?.type = CallType.INSTANT.type
                                        _callRoomName = if(callRole == CallRole.CALLER)
                                            "${LCallManager.getDisplayNameById(mySelfId)}${getString(R.string.call_instant_call_title)}"
                                        else
                                            "${callIntent.roomName}${getString(R.string.call_instant_call_title)}"
                                        callingListData[roomId]?.callName = _callRoomName
                                        LCallManager.updateCallingListData(callingListData)
                                        updateCallData(CallType.INSTANT.type)
                                    }
                                }

                            }
                        }

                        L.d { "[Call] LCallViewModel track ParticipantConnected metadata:${room.metadata}" }
                        if(!room.metadata.isNullOrEmpty()){
                            mutableRoomMetadata.value = Gson().fromJson(room.metadata, RoomMetadata::class.java)
                        }
                    }
                    is RoomEvent.Reconnected -> {
                        L.i { "[Call] LCallViewModel room reconnected" }
                        cancelCallTimeoutCheck()
                        mutableCallStatus.value = CallStatus.RECONNECTED
                    }
                    is RoomEvent.Reconnecting -> {
                        L.i { "[Call] LCallViewModel room reconnecting" }
                        mutablePrimarySpeaker.value = null
                        mutableIsParticipantShareScreen.value = false
                        mWhoSharedScreen.value = null
                        mutableCallStatus.value = CallStatus.RECONNECTING
                    }
                    is RoomEvent.Connected -> {
                        L.i { "[Call] LCallViewModel room connected" }
                        roomId?.let { roomId ->
                            LCallManager.updateCallingState(roomId, isInCalling = true)
                            if (getCurrentCallType() == CallType.ONE_ON_ONE.type) {
                                // 1v1 default microphone on
                                setMicEnabled(true)
                                if(room.remoteParticipants.size > 1) {
                                    L.i { "[Call] LCallViewModel room connected instant call" }
                                    // 1v1会议 变为instant call
                                    val callingListData = LCallManager.getCallListData()
                                    if(roomId.isNotEmpty() && callingListData?.containsKey(roomId) == true){
                                        callingListData[roomId]?.type = CallType.INSTANT.type
                                        _callRoomName = if(callRole == CallRole.CALLER)
                                            "${LCallManager.getDisplayNameById(mySelfId)}${getString(R.string.call_instant_call_title)}"
                                        else
                                            "${callIntent.roomName}${getString(R.string.call_instant_call_title)}"
                                        callingListData[roomId]?.callName = _callRoomName
                                        LCallManager.updateCallingListData(callingListData)
                                        updateCallData(CallType.INSTANT.type)
                                    }
                                    handleConnectedState()
                                }else if(room.remoteParticipants.size == 1) {
                                    L.i { "[Call] LCallViewModel room connected 1v1 call" }
                                    handleConnectedState()
                                }else {
                                    // 1v1 call, enable ongoing call timeout detection
                                    L.i { "[Call] LCallViewModel enable ongoing call timeout detection" }
                                    mutableCallStatus.value = if (callRole == CallRole.CALLER) CallStatus.CALLING else CallStatus.JOINING
                                    timeoutCheckState = TimeoutCheckState.ONGOING_CALL
                                    LCallManager.checkCallWithTimeout(LCallManager.CallState.ONGOING_CALL, LCallManager.DEF_ONGOING_CALL_TIMEOUT, roomId, callBack = {
                                        sendTimeoutBroadcast(roomId)
                                    })
                                }
                            }else {
                                handleConnectedState()
                                setMicEnabled(true, publishMuted = true)
                            }

                            L.d { "[Call] LCallViewModel track Connected metadata:${room.metadata}" }
                            if(!room.metadata.isNullOrEmpty()){
                                mutableRoomMetadata.value = Gson().fromJson(room.metadata, RoomMetadata::class.java)
                            }
                        }
                    }
                    is RoomEvent.TrackMuted -> {
                        L.i { "[Call] LCallViewModel TrackMuted:${it.participant.identity}" }
                        // Only show barrage message when microphone track is muted and subscribed
                        if(it.publication.source == Track.Source.MICROPHONE  && it.publication.muted && it.publication.subscribed){
                            showCallBarrageMessage(it.participant, getString(R.string.call_barrage_message_close_mic))
                        }
                        if(it.publication.source == Track.Source.CAMERA && it.publication.muted){
                            handleCameraTrackChange()
                        }
                    }
                    is RoomEvent.TrackUnmuted -> {
                        L.i { "[Call] LCallViewModel TrackUnmuted:${it.participant.identity}" }
                        if(it.publication.source == Track.Source.MICROPHONE){
                            showCallBarrageMessage(it.participant, getString(R.string.call_barrage_message_open_mic))
                        }
                        if(it.publication.source == Track.Source.CAMERA){
                            handleCameraTrackChange()
                        }
                    }
                    is RoomEvent.TrackSubscribed -> {
                        L.i { "[Call] LCallViewModel TrackSubscribed:${it.participant.identity}" }
                        checkRemoteUserScreenShare(it.participant)
                        if(it.publication.source == Track.Source.CAMERA && !it.publication.muted){
                            handleCameraTrackChange()
                        }
                    }
                    is RoomEvent.TrackPublished -> {
                        L.i { "[Call] LCallViewModel TrackPublished participant:${it.participant.identity}" }
                        if(getCurrentCallType() != CallType.ONE_ON_ONE.type){
                            if(it.publication.source == Track.Source.MICROPHONE  && !it.publication.muted){
                                showCallBarrageMessage(it.participant, getString(R.string.call_barrage_message_open_mic))
                            }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        L.d { "[Call] LCallViewModel TrackUnsubscribed:${it.participant.identity}" }
                        checkRemoteUserScreenShare(it.participant)
                    }
                    is RoomEvent.RoomMetadataChanged -> {
                        L.d { "[Call] LCallViewModel RoomMetadataChanged metadata:${room.metadata}" }
                        if(!room.metadata.isNullOrEmpty()){
                            mutableRoomMetadata.value = Gson().fromJson(room.metadata, RoomMetadata::class.java)
                        }
                    }
                    is RoomEvent.ConnectionQualityChanged -> {
                        L.i { "[Call] LCallViewModel ConnectionQualityChanged:${it.participant.identity} quality:${it.quality}" }
                        if(getCurrentCallType() == CallType.ONE_ON_ONE.type){
                            if(it.participant is RemoteParticipant && it.quality != ConnectionQuality.EXCELLENT && it.quality != ConnectionQuality.GOOD){
                                mutableError.value = NetworkConnectionPoorException(getString(R.string.call_other_network_poor_tip))
                            }
                        }
                        if(it.participant is LocalParticipant && it.quality != ConnectionQuality.EXCELLENT && it.quality != ConnectionQuality.GOOD){
                            mutableError.value = NetworkConnectionPoorException(getString(R.string.call_myself_network_poor_tip))
                        }
                    }
                    is RoomEvent.TrackSubscriptionFailed -> {
                        L.e { "[Call] LCallViewModel TrackSubscriptionFailed:${it.participant.identity} exception:${it.exception}" }
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        L.d { "[call] LCallViewModel ActiveSpeakersChanged speakers: ${it.speakers.map { it.identity?.value }}" }
                        // Filter out speakers that are not in the participants list.
                        val filteredSpeakers = it.speakers.filter { speaker -> speaker in participants.value }
                        if(lastSpeakers == filteredSpeakers || hasSpeaker && filteredSpeakers.isEmpty()) {
                            return@collect
                        }
                        lastSpeakers = filteredSpeakers

                        debounceSpeakerUpdateJob?.cancel()
                        debounceSpeakerUpdateJob = launch(Dispatchers.Default) {
                            val delayTime: Long = if (filteredSpeakers.isEmpty()){
                                hasSpeaker = false
                                2500
                            } else {
                                hasSpeaker = true
                                200
                            }
                            delay(delayTime)
                            withContext(Dispatchers.Main) {
                                participantsSortBySpeaker(filteredSpeakers)
                                handlePrimarySpeaker(activeSpeakers = filteredSpeakers)
                                hasSpeaker = false
                            }
                        }
                    }
                    else -> {
                        L.d { "[call] LCallViewModel Room event: $it" }
                    }
                }
            }
        }
    }

    /**
     * Registers a collector for errors within the ViewModel scope.
     */
    private fun registerErrorCollector() {
        viewModelScope.launch {
            error.collect {
                L.e { it?.stackTraceToString().toString() }
            }
        }
    }

}

private fun <T> LiveData<T>.hide(): LiveData<T> = this
private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
private fun <T> Flow<T>.hide(): Flow<T> = this