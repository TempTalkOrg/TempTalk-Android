package com.difft.android.call.core

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import io.livekit.android.room.Room
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

    private val roomLock = Any()

    // @Volatile: the fail-loud [room] getter reads roomInstance OUTSIDE roomLock; @Volatile gives the
    // cross-thread happens-before so a reader on any thread sees the value written under the lock by createRoom().
    @Volatile
    private var roomInstance: Room? = null

    // @Volatile, lock-free: set TRUE before disconnectAndRelease() enters synchronized(roomLock). Because the
    // write happens-before the releasing thread blocks on the lock, a lock-free isReleaseIntended() read is
    // guaranteed to observe it once createRoom() releases the lock — independent of who wins roomLock next.
    @Volatile
    private var releaseIntended = false

    // Drives createRoom()'s in-lock post-create release branch. Only ever touched under roomLock.
    private var releaseRequested = false

    // The room has actually been released (idempotency). Mutated only under roomLock;
    // @Volatile so the lock-free [room] getter can fail-loud on post-release access.
    @Volatile
    private var released = false

    /**
     * Phase B entry. Creates the WebRTC room (LiveKit.create() native init, ~624 ms) under [roomLock] on the
     * caller's thread. If [releaseRequested] was set before we acquired the lock, the freshly-created room is
     * released immediately post-create (and still returned). Idempotent: repeat calls return the existing instance.
     */
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
            // release raced in before we acquired the lock → release now
            L.i { "[Call] createRoom: release requested during create, releasing immediately" }
            releaseLocked()
        }
        created
    }

    /**
     * Non-creating, fail-loud getter. Reading before [createRoom] throws (the on-main-creation bug guard);
     * reading after release also throws so a stale, already-released [Room] is never handed out.
     */
    val room: Room
        get() {
            check(!released) { "[Call] room accessed after release" }
            return roomInstance ?: error("[Call] room accessed before createRoom()")
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
            // Call-scoped tunnel: gated by "Protect IP address in calls". When the
            // toggle is off the signaling connection goes DIRECT even while the proxy
            // is active for the IM plane (currentForCall returns null → plain socket).
            .dns(ProxyTunnelDns(proxyConfigProvider, forCall = true))
            .socketFactory(ProxyTunnelSocketFactory(proxyConfigProvider, forCall = true))
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
        // Call-plane gate: only enforce TURN-mandatory when the proxy actually routes
        // calls ("Protect IP address in calls" ON). currentForCall already returns null
        // unless protect-on AND the proxy is active, so a SINGLE read covers both the
        // gate and the snapshot — no TOCTOU double-read of routingState (matches the
        // single-read contract in connect()). With the toggle off the call goes direct,
        // so a TURN-less proxy must not block it.
        val config = proxyConfigProvider.currentForCall ?: return false
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
     * Returns null when no TURN is configured; the call then keeps stock behavior
     * (signaling tunneled, media direct).
     *
     * The "proxy active for calls" gate lives at the SINGLE call site
     * (`proxyConfig?.let { buildProxyRtcConfig(it) }` in [connect]): a non-null
     * [config] already came from one `currentForCall` read, so re-reading
     * `routingState` here would be a second load and reintroduce the TOCTOU the
     * single-read contract avoids. Derive purely from the captured [config].
     */
    private fun buildProxyRtcConfig(config: ProxyConfig): PeerConnection.RTCConfiguration? {
        if (!config.turnEnabled()) return null
        val (user, credential) = config.turnCredentials() ?: return null

        // Stealth mode: the ONLY relay candidate is turns:443 (TURN over TLS).
        // Media is wrapped in an ordinary-looking TLS flow to :443, so a DPI box
        // never sees a TURN/STUN handshake on the well-known 3478 — the proxy
        // operator does not even publish 3478 (see docker-compose.yml). The server
        // demuxes 443 by SNI: libwebrtc dials turns:<ip>:443 and (verified
        // empirically) sends the IP literal AS the ClientHello SNI — NOT an empty
        // SNI. The server keys on that: an IP-shaped (or empty) SNI -> coturn:5349,
        // while a hostname SNI (the signaling tunnel's decoy) -> the TLS terminator
        // (see Yelling-TLS-Proxy data/nginx-terminate/nginx.conf). The IP-literal
        // SNI is therefore load-bearing for media routing — do not override it with
        // the decoy hostname here, or media would be misrouted to the signaling path,
        // UNLESS the server-side SNI demux is updated in lockstep to route that
        // hostname to coturn (a distinct media decoy, kept separate from the signaling
        // decoy since the TURN-TLS flow carries no ALPN to demux on instead).
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

            // SINGLE read of proxyConfigProvider.currentForCall — both rtcConfig and
            // the verifier derive from this one snapshot so they cannot drift (TOCTOU).
            // currentForCall is null when "Protect IP address in calls" is off, so the
            // call connects directly with no TURN/QUIC proxy injection.
            val proxyConfig = proxyConfigProvider.currentForCall
            val proxyRtcConfig = proxyConfig?.let { buildProxyRtcConfig(it) }

            // QUIC-over-proxy (standards RFC 9298 CONNECT-UDP, design §9.6): only when
            // QUIC signaling is active AND the proxy advertises a QUIC relay (`q`). The
            // inner QUIC keeps verifying the SFU via [certPem] (chative CA); the OUTER hop
            // to the proxy is a separate HTTP/3 CONNECT-UDP connection. The outer hop is now
            // SPKI-pinned to proxyConfig.spkiPinBase64 (passed as quicProxySpkiPin below) —
            // the SAME pin used for the WSS tunnel and the coturn TURN-TLS layer. The CA-chain
            // path is unused (quicProxyCaCertPem stays null); the pin IS the verification.
            // Inner traffic stays E2E. WSS mode ignores these fields.
            val quicProxy = proxyConfig?.takeIf { useQuicSignal && it.quicEnabled }
            if (quicProxy != null) {
                L.i { "[Call] QUIC-over-proxy enabled host=${quicProxy.host} port=${quicProxy.port}" }
            }

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
                    quicCidTag = mySelfId.replace("+", "").trim(),
                    quicProxyHost = quicProxy?.host,
                    quicProxyPort = quicProxy?.port ?: ProxyConfig.DEFAULT_PORT,
                    // outerSni() never returns the IP host: an IP literal is illegal in the
                    // TLS SNI extension (RFC 6066) and gets rejected/ignored, breaking the
                    // QUIC tunnel for IP-addressed proxies. Mirrors the WSS tunnel + probe paths.
                    quicProxySni = quicProxy?.outerSni(),
                    quicProxyCaCertPem = null,
                    quicProxySpkiPin = quicProxy?.spkiPinBase64,
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
        releaseIntended = true // FIRST, lock-free: visible to Phase B's pre-wiring guard (see design §3.3)
        synchronized(roomLock) {
            releaseRequested = true // honored by createRoom() if room not yet created
            releaseLocked() // releases now if already created; no-op otherwise
        }
    }

    private fun releaseLocked() {
        val r = roomInstance ?: return // not created yet → createRoom() will release post-create
        if (released) return // idempotent: never double-release
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

    /**
     * Lock-free. True from the instant a release is requested (set before disconnectAndRelease takes the lock).
     * Called by production Phase B wiring ([com.difft.android.call.LCallViewModel.startRoomDependentWiring]),
     * so this is `internal` (not `@VisibleForTesting`) — same-module tests still reach it.
     */
    internal fun isReleaseIntended(): Boolean = releaseIntended

    /** True once the room has actually been released (post-lock). */
    @VisibleForTesting
    fun isReleased(): Boolean = synchronized(roomLock) { released }

    fun local(): LocalParticipant = room.localParticipant
}
