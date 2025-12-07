package com.difft.android.call.core

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.CallIntent
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.exception.StartCallException
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.TTEncryptor
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoPreset169
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import livekit.LivekitTemptalk
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor

class CallRoomController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val callIntent: CallIntent,
    private val audioHandler: AudioSwitchHandler,
    private val audioProcessor: DenoisePluginAudioProcessor?,
    private val e2eeEnable: Boolean,
    private val decryptCallMKey: (eKey: String, eMKey: String) -> ByteArray?,
) {
    private val _callStatus = MutableStateFlow(if (callIntent.action == CallIntent.Action.START_CALL) CallStatus.CALLING else CallStatus.JOINING)
    val callStatus = _callStatus.asStateFlow()

    private val _callType = MutableStateFlow(callIntent.callType)
    val callType = _callType.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    val cameraEnabled = _cameraEnabled.asStateFlow()

    private val _isNoSpeakSoloTimeout = MutableStateFlow(false)
    val isNoSpeakSoloTimeout = _isNoSpeakSoloTimeout.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()

    private val _roomMetadata = MutableStateFlow(RoomMetadata(canPublishAudio = true, canPublishVideo = true))
    val roomMetadata = _roomMetadata.asStateFlow()

    @Volatile
    private var isUseQuicSignal = false
    fun isUseQuicSignal(): Boolean = isUseQuicSignal

    val room by lazy {
        LiveKit.create(
            appContext = appContext,
            options = getRoomOptions(),
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioHandler = audioHandler,
                    audioProcessorOptions = AudioProcessorOptions(capturePostProcessor = audioProcessor)
                )
            )
        )
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
                captureParams = VideoCaptureParameter(1280, 720, 30),
                isPortrait = true // Set portrait mode for vertical video capture orientation
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 20_000,
                dtx = true,
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoPreset169.H1080.encoding,
                videoCodec = VideoCodec.VP8.codecName,
                scalabilityMode = "L3T3"
            )
        )
    }

    private fun getE2EEOptions(): E2EEOptions? = if (!e2eeEnable) null else E2EEOptions().apply {
        ttEncryptor = object : TTEncryptor {
            override fun decryptCallKey(eKey: String, eMKey: String): ByteArray? = decryptCallMKey(eKey, eMKey)
        }
    }

    suspend fun connect(url: String, appToken: String, startCallParams: ByteArray, useQuicSignal: Boolean, onError: (Throwable) -> Unit) = withContext(
        Dispatchers.IO) {
        try {
            room.e2eeOptions = getE2EEOptions()
            isUseQuicSignal = useQuicSignal
            room.connect(
                url = url,
                token = "",
                options = ConnectOptions(
                    ttCallRequest = LivekitTemptalk.TTCallRequest
                        .newBuilder()
                        .setToken(appToken)
                        .setStartCall(livekit.LivekitTemptalk.TTStartCall.parseFrom(startCallParams))
                        .build(),
                    useQuicSignal = useQuicSignal
                )
            )
            L.i { "[Call] connectToRoom connected" }
        } catch (t: Throwable) {
            onError(t)
        }
    }

    fun updateCallStatus(status: CallStatus) {
        _callStatus.value = status
    }

    fun updateCallType(type: String) {
        _callType.value = type
    }

    fun updateMicEnabled(enabled: Boolean) {
        _micEnabled.value = enabled
    }

    fun updateCameraEnabled(enabled: Boolean) {
        _cameraEnabled.value = enabled
    }

    fun updateNoSpeakSoloTimeout(timeout: Boolean) {
        _isNoSpeakSoloTimeout.value = timeout
    }

    fun updateRoomMetadata(metadata: RoomMetadata) {
        _roomMetadata.value = metadata
    }

    fun collectError(error: Throwable?) {
        _error.value = error
    }

    fun disconnectAndRelease() {
        runCatching { room.disconnect() }
        runCatching { room.release() }
        room.e2eeOptions?.ttEncryptor = null
        room.e2eeOptions = null
    }

    fun local(): LocalParticipant = room.localParticipant
}