package com.difft.android.call.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.call.CallIntent
import com.difft.android.call.connect.MeetingConnectionPlanner
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.exception.CallPreconditionException
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyTunnelDns
import com.difft.android.network.proxy.ProxyTunnelSocketFactory
import okhttp3.OkHttpClient
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.TTEncryptor
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoPreset169
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SSLCertificateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import livekit.LivekitTemptalk
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import androidx.core.net.toUri

private const val QUIC_CONNECT_TIMEOUT_MS = 3000

class CallRoomController(
    private val appContext: Context,
    callIntent: CallIntent,
    private val audioHandler: AudioSwitchHandler,
    private val audioProcessor: AudioPipelineProcessor?,
    private val e2eeEnable: Boolean,
    private val proxyConfigProvider: ProxyConfigProvider,
    private val decryptCallMKey: (eKey: String, eMKey: String) -> ByteArray?,
) {
    private val _callStatus = MutableStateFlow(if (callIntent.action == CallIntent.Action.START_CALL) CallStatus.CALLING else CallStatus.JOINING)
    val callStatus = _callStatus.asStateFlow()

    private val _callType = MutableStateFlow(callIntent.callType)
    val callType = _callType.asStateFlow()

    
    val startedOneOnOne = callIntent.action == CallIntent.Action.START_CALL &&
        callIntent.callType == CallType.ONE_ON_ONE.type

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    val cameraEnabled = _cameraEnabled.asStateFlow()

    private val _isNoSpeakSoloTimeout = MutableStateFlow(false)
    val isNoSpeakSoloTimeout = _isNoSpeakSoloTimeout.asStateFlow()

    private val _error = Channel<Throwable>(Channel.BUFFERED)
    val error = _error.receiveAsFlow()

    private val _roomMetadata = MutableStateFlow(RoomMetadata(canPublishAudio = true, canPublishVideo = true))
    val roomMetadata = _roomMetadata.asStateFlow()

    @Volatile
    private var isUseQuicSignal = false
    fun isUseQuicSignal(): Boolean = isUseQuicSignal

    private val mySelfId: String by lazy { globalServices.myId }

    private val roomLock = Any()

    
    
    @Volatile
    private var roomInstance: Room? = null

    
    
    
    @Volatile
    private var releaseIntended = false

    
    private var releaseRequested = false

    
    
    @Volatile
    private var released = false

    


    internal fun createRoom(): Room = synchronized(roomLock) {
        roomInstance?.let { return it }
        val created = LiveKit.create(
            appContext = appContext,
            options = getRoomOptions(),
            overrides = LiveKitOverrides(
                okHttpClient = buildSignalingOkHttpClient(),
                audioOptions = AudioOptions(
                    audioHandler = audioHandler,
                    audioProcessorOptions = AudioProcessorOptions(capturePostProcessor = audioProcessor)
                )
            )
        )
        roomInstance = created
        if (releaseRequested) {
            
            L.i { "[Call] createRoom: release requested during create, releasing immediately" }
            releaseLocked()
        }
        created
    }

    









    val room: Room
        get() {
            if (released) throw CallPreconditionException("[Call] room accessed after release")
            return roomInstance ?: throw CallPreconditionException("[Call] room accessed before createRoom()")
        }

    






    fun roomStateOrNull(): Room.State? {
        if (released) return null
        return roomInstance?.state
    }

    






    fun roomOrNull(): Room? {
        if (released) return null
        return roomInstance
    }

    





    fun isRoomDisconnected(): Boolean {
        if (released) return true
        return (roomInstance?.state ?: Room.State.DISCONNECTED) == Room.State.DISCONNECTED
    }

    





    fun hasRemoteParticipants(): Boolean {
        if (released) return false
        return roomInstance?.remoteParticipants?.isNotEmpty() == true
    }

    






    fun disconnectQuietly() {
        if (released) return
        val r = roomInstance ?: return
        runCatching { r.disconnect() }
    }

    


    
    
    
    
    
    @android.annotation.SuppressLint("ChativeHttpClientRequired")
    private fun buildSignalingOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            
            
            
            .dns(ProxyTunnelDns(proxyConfigProvider, forCall = true))
            .socketFactory(ProxyTunnelSocketFactory(proxyConfigProvider, forCall = true))
            .build()

    

    fun isProxyActiveWithoutTurn(): Boolean {
        
        
        
        
        
        
        val config = proxyConfigProvider.currentForCall ?: return false
        return !config.turnEnabled()
    }

    
















    private fun buildProxyRtcConfig(config: ProxyConfig): PeerConnection.RTCConfiguration? {
        if (!config.turnEnabled()) return null
        val (user, credential) = config.turnCredentials() ?: return null

        
        
        
        
        
        val turnsUrl = "turns:${config.host}:${config.port}?transport=tcp"
        val iceServers = listOf(
            PeerConnection.IceServer.builder(listOf(turnsUrl))
                .setUsername(user)
                .setPassword(credential)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                .createIceServer(),
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
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
                isPortrait = true 
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

    suspend fun connect(domain: String, url: String, certPem: String, appToken: String, startCallParams: ByteArray, useQuicSignal: Boolean, onError: (Throwable) -> Unit) = withContext(
        Dispatchers.IO) {
        try {
            if (!useQuicSignal) {
                val host = runCatching { url.toUri().host.orEmpty() }.getOrNull().orEmpty()
                if (host.isNotEmpty() && MeetingConnectionPlanner.isIpHost(host)) {
                    L.e {
                        "[Call] WSS must use domain, but got IP host=$host url=$url serverHost=$domain (likely regression)"
                    }
                }
            }
            room.e2eeOptions = getE2EEOptions()
            isUseQuicSignal = useQuicSignal

            
            
            
            
            val proxyConfig = proxyConfigProvider.currentForCall
            val proxyRtcConfig = proxyConfig?.let { buildProxyRtcConfig(it) }

            
            
            
            
            
            val quicProxy = proxyConfig?.takeIf { useQuicSignal && it.quicEnabled }
            if (quicProxy != null) {
                L.i { "[Call] QUIC-over-proxy enabled host=${quicProxy.host} port=${quicProxy.port}" }
            }
            val forcePhysical = useQuicSignal && proxyConfig == null
            val physicalNetworkHandle =
                if (forcePhysical) appContext.physicalNetworkHandle() else 0

            
            
            
            
            val turnTlsVerifier: SSLCertificateVerifier? = proxyRtcConfig?.let {
                val pin = proxyConfig?.spkiPinBase64
                if (pin.isNullOrBlank()) {
                    
                    
                    
                    
                    throw CallPreconditionException(
                        "[Call] proxy TURN relay active but SPKI pin absent — refusing unverified connect"
                    )
                }
                ProxyTurnTlsVerifier(pin)
            }

            if (proxyRtcConfig != null) {
                L.i { "[Call] proxy media relay enabled (TURN-only ICE, SPKI-pinned TLS)" }
            }
            room.connect(
                url = url,
                token = "",
                options =
                    ConnectOptions(
                    caCertPem = certPem,
                    serverHost = domain,
                    rtcConfig = proxyRtcConfig,
                    sslCertificateVerifier = turnTlsVerifier,
                    ttCallRequest = LivekitTemptalk.TTCallRequest
                        .newBuilder()
                        .setToken(appToken)
                        .setStartCall(LivekitTemptalk.TTStartCall.parseFrom(startCallParams))
                        .build(),
                    useQuicSignal = useQuicSignal,
                    quicDeviceType = DEFAULT_DEVICE_ID,
                    quicCidTag = mySelfId.replace("+", "").trim(),
                    quicProxyHost = quicProxy?.host,
                    quicProxyPort = quicProxy?.port ?: ProxyConfig.DEFAULT_PORT,
                    
                    
                    
                    quicProxySni = quicProxy?.outerSni(),
                    quicProxyCaCertPem = null,
                    quicProxySpkiPin = quicProxy?.spkiPinBase64,
                    quicConnectTimeoutMs = QUIC_CONNECT_TIMEOUT_MS,
                    forcePhysical = forcePhysical,
                    physicalNetworkHandle = physicalNetworkHandle,
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

    fun collectError(error: Throwable) {
        _error.trySend(error)
    }

    fun disconnectAndRelease() {
        releaseIntended = true 
        synchronized(roomLock) {
            releaseRequested = true 
            releaseLocked() 
        }
    }

    private fun releaseLocked() {
        val r = roomInstance ?: return 
        if (released) return 
        released = true
        runCatching { r.disconnect() }
        runCatching {
            r.e2eeOptions?.ttEncryptor = null
            r.e2eeOptions = null
        }
        runCatching { r.release() }
    }

    @VisibleForTesting
    fun isRoomInitialized(): Boolean = synchronized(roomLock) { roomInstance != null }

    

    internal fun isReleaseIntended(): Boolean = releaseIntended

    
    @VisibleForTesting
    fun isReleased(): Boolean = synchronized(roomLock) { released }
}
