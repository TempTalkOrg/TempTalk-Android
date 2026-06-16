package com.difft.android.call.core

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.call.CallIntent
import com.difft.android.call.connect.MeetingConnectionPlanner
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RoomMetadata
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
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.LocalParticipant
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

    val room by lazy {
        LiveKit.create(
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
    }

    /**
     * OkHttpClient handed to LiveKit so its **WSS signaling** rides the self-hosted
     * proxy tunnel when active. `WebSocketTransport.configureClient()` derives from
     * this via `newBuilder().sslSocketFactory(caCertPem)`, which preserves our [Dns]
     * and [javax.net.SocketFactory] — yielding the intended TLS-in-TLS (outer pinned
     * tunnel + inner call-CA TLS). When the proxy is disabled both are no-ops
     * (system DNS + plain socket), so behavior matches stock LiveKit. WebRTC media is
     * unaffected and still cannot traverse the tunnel (see proxy design §9.3); step 1
     * forces WSS-over-domain so the QUIC path (which bypasses OkHttp) is not taken.
     */
    // Legitimate exception to ChativeHttpClientRequired: this is LiveKit's signaling
    // client, not a TempTalk auth-bearing API call. It deliberately carries no auth
    // interceptor and instead installs the proxy-tunnel Dns + SocketFactory so WSS
    // signaling rides the self-hosted tunnel; routing it through ChativeHttpClient
    // would add auth headers LiveKit must not send and drop the tunnel wiring.
    @android.annotation.SuppressLint("ChativeHttpClientRequired")
    private fun buildSignalingOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .dns(ProxyTunnelDns(proxyConfigProvider))
            .socketFactory(ProxyTunnelSocketFactory(proxyConfigProvider))
            .build()

    /**
     * Privacy guard for "TURN-mandatory" mode. The proxy exists to hide the client's
     * real IP from TempTalk (design §1.1/§1.3). WebRTC **media** only avoids leaking
     * that IP when it is relayed through the operator's TURN server (see
     * [buildProxyRtcConfig]). If the proxy is active but no TURN secret is configured,
     * media would fall back to a direct path and expose the client IP to the SFU — so
     * the call must be refused rather than silently leaking.
     *
     * @return true when the proxy is active but TURN is missing (caller should block).
     */
    fun isProxyActiveWithoutTurn(): Boolean {
        if (!ProxyConfigProvider.isProxyActive) return false
        val config = proxyConfigProvider.current ?: return false
        return !config.turnEnabled()
    }

    /**
     * Builds a relay-only [PeerConnection.RTCConfiguration] that forces WebRTC
     * **media** through the operator's TURN server when the proxy is active and a
     * TURN secret is present in the share code. Passing our own ICE servers makes
     * LiveKit ignore the server-advertised ones (see `RTCEngine.makeRTCConfig`),
     * and [PeerConnection.IceTransportsType.RELAY] suppresses host/srflx
     * candidates — so the client's real IP never reaches TempTalk's media path.
     *
     * Returns null when the proxy is off or no TURN is configured; the call then
     * keeps stock behavior (signaling tunneled, media direct).
     */
    private fun buildProxyRtcConfig(config: ProxyConfig): PeerConnection.RTCConfiguration? {
        if (!ProxyConfigProvider.isProxyActive) return null
        if (!config.turnEnabled()) return null
        val (user, credential) = config.turnCredentials() ?: return null

        // Stealth mode: the ONLY relay candidate is turns:443 (TURN over TLS).
        // Media is wrapped in an ordinary-looking TLS flow to :443, so a DPI box
        // never sees a TURN/STUN handshake on the well-known 3478 — the proxy
        // operator does not even publish 3478 (see docker-compose.yml). The server
        // demuxes 443 by SNI: turns over an IP literal sends NO SNI -> routed to
        // coturn:5349 (the signaling tunnel sends a non-empty decoy SNI instead).
        // TLS_CERT_POLICY_SECURE so WebRTC invokes the custom SSLCertificateVerifier
        // injected via ConnectOptions (see connect()). The verifier SPKI-pins coturn's
        // self-signed leaf to ProxyConfig.spkiPinBase64 — same pin as the signaling
        // tunnel. INSECURE_NO_CHECK is NOT used: it would route the handshake through
        // SSL_VERIFY_NONE and skip the verifier (the outer TLS would be unverified).
        // Media stays DTLS-SRTP end-to-end; the TURN credential is a short-lived HMAC.
        // RELAY-only ICE (below) means dropping the plain turn:3478 candidates costs no
        // privacy and removes the wasted 3478 gathering attempts; the trade-off is no
        // lower-latency UDP fallback — acceptable since privacy/anti-blocking is the
        // primary goal.
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

            // SINGLE read of proxyConfigProvider.current — both rtcConfig and the
            // verifier derive from this one snapshot so they cannot drift (TOCTOU).
            val proxyConfig = proxyConfigProvider.current
            val proxyRtcConfig = proxyConfig?.let { buildProxyRtcConfig(it) }

            // Tie the verifier to the SAME gate as proxyRtcConfig so they can't drift:
            // a non-null proxyRtcConfig was produced from proxyConfig, so the safe-call
            // pin read below is non-null on that path. spkiPinBase64 is a non-null String
            // (ProxyConfig.kt:35); isNullOrBlank() guards the empty/degenerate share link.
            val turnTlsVerifier: SSLCertificateVerifier? = proxyRtcConfig?.let {
                val pin = proxyConfig?.spkiPinBase64
                if (pin.isNullOrBlank()) {
                    // Fail-CLOSED: TURN relay is forced but we have no pin to verify
                    // coturn's self-signed leaf. Refusing beats connecting unverified.
                    throw IllegalStateException(
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
                    quicCidTag = mySelfId.replace("+", "").trim()
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
        runCatching { room.disconnect() }
        runCatching {
            room.e2eeOptions?.ttEncryptor = null
            room.e2eeOptions = null
        }
        runCatching { room.release() }
    }

    fun local(): LocalParticipant = room.localParticipant
}
