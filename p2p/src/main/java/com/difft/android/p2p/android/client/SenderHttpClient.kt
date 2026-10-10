package com.difft.android.p2p.android.client

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.io.FileSource
import com.difft.android.p2p.engine.model.ClaimRequest
import com.difft.android.p2p.engine.model.CancelResponse
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.LinkIntentRequest
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.LinkPeerStateResponse
import com.difft.android.p2p.engine.model.LinkPrecheckRequest
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.PrepareUploadResponse
import com.difft.android.p2p.engine.model.QrPayload
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer

/**
 * Sender side of the transfer: takes an [OkHttpClient] pinned to the QR's `fp` from
 * [PinnedHttpClients] (that fingerprint only — MITM defense), re-times it per endpoint, then drives
 * `register` / `prepare-upload?pin=` / `upload` / `cancel`.
 *
 * The upload streams the [FileSource] as a streaming [RequestBody], no full-file buffering.
 */
class SenderHttpClient(
    private val ip: String,
    private val port: Int,
    private val fingerprint: String,
) {

    /** QR pairing constructor: pulls {ip, port, fp} from the scanned payload. */
    constructor(target: QrPayload) : this(target.ip, target.port, target.fp)

    private val baseUrl = "https://$ip:$port/api/localsend/v2"

    /** Our own extension endpoints live under a separate prefix so upstream LocalSend additions cannot collide. */
    private val migrationBaseUrl = "https://$ip:$port/api/migration/v1"

    /** The pinned base every client here is re-timed from; see [PinnedHttpClients]. */
    private val defaultClient: OkHttpClient by lazy { PinnedHttpClients.pinned(fingerprint) }

    /**
     * Test-only override for [client]: lets a test inject a mocked client whose `newCall` returns a
     * controllable [okhttp3.Call], so the upload cancel bridge can be driven without a real socket.
     * Production code never sets this.
     */
    @VisibleForTesting
    internal var clientOverride: OkHttpClient? = null

    private val client: OkHttpClient
        get() = clientOverride ?: defaultClient

    /**
     * The upload call waits on a much longer read deadline: its response is the receiver's verdict,
     * withheld until the container is verified, imported and promoted — minutes of silence on a large
     * history, under which the default deadline would have the sender report failure while the
     * receiver succeeds. A deadline still exists so a silently dead receiver cannot hang the sender
     * forever; the Android receiver's `102 Processing` beat restarts it, and it stays at 15 minutes
     * until the iOS and macOS receivers beat too.
     *
     * The write deadline is the body's stall watchdog: a receiver that drops off the network (its
     * radio off, the link gone) sends nothing back, so the socket only stops accepting bytes once its
     * buffers fill, and TCP alone takes many minutes to give up. A single write that sits for
     * [UPLOAD_WRITE_STALL_SECONDS] without the peer taking any of it fails the upload as unreachable
     * instead: the client-side counterpart of the receiver's 30 s idle-read timeout, with twice the
     * headroom because a write can legitimately wait on the receiver's import. A live receiver drains
     * the socket as it imports, so an alive-but-slow peer never sits that long.
     */
    private val uploadClient: OkHttpClient by lazy {
        defaultClient.newBuilder()
            .readTimeout(UPLOAD_VERDICT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(UPLOAD_WRITE_STALL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val clientForUpload: OkHttpClient
        get() = clientOverride ?: uploadClient

    /** `/status` is a quick question on its own connection: a receiver that cannot answer it in seconds is not answering. */
    private val statusClient: OkHttpClient by lazy {
        defaultClient.newBuilder().callTimeout(STATUS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    /** Optional handshake. Returns the receiver's [DeviceInfo]. */
    suspend fun register(self: DeviceInfo): DeviceInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/register")
            .post(P2pJson.encodeToString(self).jsonBody())
            .build()
        client.newCall(req).await().use { resp ->
            check(resp.isSuccessful) { "register failed ${resp.code}" }
            P2pJson.decodeFromString(resp.body.string())
        }
    }

    /**
     * `prepare-upload`. [pin] is the QR's one-time PIN for the pairing path; pass null when sending
     * to a plain LocalSend peer, which does not require it — the `?pin=` query is then omitted.
     */
    suspend fun prepareUpload(self: DeviceInfo, file: FileMetadata, pin: String?): PrepareUploadResponse =
        prepareUpload(self, mapOf(file.id to file), pin)

    /**
     * Migration variant: carries the old device's [MigrationHandshake] in the `prepare-upload` body
     * so the new device's receiver gate can fail fast on env/account/version before accepting the
     * session. A 403 is the typed reject the caller maps to a specific "can't migrate" message.
     */
    suspend fun prepareUpload(
        self: DeviceInfo,
        file: FileMetadata,
        pin: String?,
        handshake: MigrationHandshake,
    ): PrepareUploadResponse = prepareUpload(self, mapOf(file.id to file), pin, handshake)

    /**
     * Multi-file variant of [prepareUpload]: one session may carry several files, each with its own
     * upload token. [handshake] is the migration-only source-identity extension — null for generic
     * file transfer, and then omitted from the wire entirely.
     */
    suspend fun prepareUpload(
        self: DeviceInfo,
        files: Map<String, FileMetadata>,
        pin: String?,
        handshake: MigrationHandshake? = null,
    ): PrepareUploadResponse =
        withContext(Dispatchers.IO) {
            val request = PrepareUploadRequest(info = self, files = files, migration = handshake)
            val pinQuery = if (pin != null) "?pin=$pin" else ""
            val req = Request.Builder()
                .url("$baseUrl/prepare-upload$pinQuery")
                .post(P2pJson.encodeToString(request).jsonBody())
                .build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) PrepareRejection.throwRejection("prepare-upload", resp)
                L.i { "[P2P][Sender] prepared sessionId via ${resp.code} files=${files.size}" }
                P2pJson.decodeFromString(resp.body.string())
            }
        }

    /**
     * Streams [source] to `upload`. [onProgress] receives (bytesSent, total) as the body streams;
     * [onBodySent] fires once the last byte has been handed to the socket, after which the sender is
     * only waiting for the receiver's verdict. Returns true on a 200.
     *
     * Body framing follows [FileSource.size]: a non-negative size gives known `Content-Length`
     * framing and a real `total`; a negative size means unknown length, so OkHttp emits
     * `Transfer-Encoding: chunked` (the lazily streamed migration container) and `total` is 0.
     */
    suspend fun upload(
        sessionId: String,
        fileId: String,
        token: String,
        source: FileSource,
        // Declared before onProgress on purpose: a trailing lambda binds to the last parameter, and
        // callers pass their progress callback that way.
        onBodySent: () -> Unit = {},
        // The 200's headers, for a sender that must read an explicit acknowledgment before it acts
        // ([com.difft.android.p2p.engine.upload.UploadReplyHeaders]). Not fired on a non-2xx.
        onReply: (headers: Map<String, String>) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val body = StreamingRequestBody(source, onProgress, onBodySent)
        val req = Request.Builder()
            .url("$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token")
            .post(body)
            .build()
        clientForUpload.newCall(req).await().use { resp ->
            L.i { "[P2P][Sender] upload fileId=$fileId result=${resp.code}" }
            if (resp.isSuccessful) onReply(resp.headers.names().associateWith { resp.header(it).orEmpty() })
            resp.isSuccessful
        }
    }

    /**
     * The two calls a phone makes from the Desktop-login confirmation page sit on the login's
     * critical path, so they must fail fast when the Desktop is not reachable on this network: 3 s to
     * connect, 5 s for the whole call. The Desktop answers both in milliseconds.
     */
    private val linkIntentClient: OkHttpClient by lazy {
        defaultClient.newBuilder()
            .connectTimeout(LINK_INTENT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LINK_INTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LINK_INTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val clientForLinkIntent: OkHttpClient
        get() = clientOverride ?: linkIntentClient

    /**
     * `link-precheck`, asked on the pinned connection before the confirmation page renders: it
     * answers how the Desktop's retained history relates to this account and doubles as the
     * reachability probe. Does not freeze the PIN. Rejections map like `prepare-upload`'s; a
     * connection failure propagates so the page can offer a plain sign-in without sync. An unknown
     * state from a newer Desktop reads as [LinkPeerState.CLEAN].
     */
    suspend fun linkPrecheck(self: DeviceInfo, pin: String?, handshake: MigrationHandshake): LinkPeerState =
        withContext(Dispatchers.IO) {
            val pinQuery = if (pin != null) "?pin=$pin" else ""
            val req = Request.Builder()
                .url("$migrationBaseUrl/link-precheck$pinQuery")
                .post(P2pJson.encodeToString(LinkPrecheckRequest(info = self, migration = handshake)).jsonBody())
                .build()
            clientForLinkIntent.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) PrepareRejection.throwRejection("link-precheck", resp)
                val state = peerState(resp.body.string()) ?: LinkPeerState.CLEAN
                L.i { "[P2P][Sender] link-precheck state=${state.wire}" }
                state
            }
        }

    /**
     * `link-intent`: the phone's sync decision, delivered before it links so the Desktop decides at
     * provisioning time with no waiting. Also freezes the pairing PIN on the Desktop and, on an
     * account switch with sync on, arms its login gate. Returns the Desktop's own verdict on the
     * account relation, which supersedes the precheck's; null from an older Desktop whose reply
     * carries no body. Rejections map like `prepare-upload`'s (a stale PIN is a 401 → the user
     * re-scans); a connection failure propagates so the caller can fall back to a plain sign-in.
     */
    suspend fun linkIntent(self: DeviceInfo, pin: String?, handshake: MigrationHandshake, sync: Boolean): LinkPeerState? =
        withContext(Dispatchers.IO) {
            val pinQuery = if (pin != null) "?pin=$pin" else ""
            val req = Request.Builder()
                .url("$migrationBaseUrl/link-intent$pinQuery")
                .post(P2pJson.encodeToString(LinkIntentRequest(info = self, migration = handshake, sync = sync)).jsonBody())
                .build()
            clientForLinkIntent.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) PrepareRejection.throwRejection("link-intent", resp)
                val state = peerState(resp.body.string())
                L.i { "[P2P][Sender] link-intent accepted sync=$sync state=${state?.wire}" }
                state
            }
        }

    /** Outcome of [claimPairing]. */
    enum class PairingClaim { ACCEPTED, EXPIRED, UNREACHABLE }

    /**
     * `claim`: announce this device to the receiver the moment its code was read, so the code stops
     * rotating while the user reads the confirmation page. A code that has already rotated is refused
     * here (401 → [PairingClaim.EXPIRED]) rather than at `prepare-upload` minutes later. A receiver
     * that predates the endpoint answers 404 and is [PairingClaim.ACCEPTED]; one that cannot be
     * reached is [PairingClaim.UNREACHABLE]. The caller lets the handshake decide in both cases.
     */
    suspend fun claimPairing(self: DeviceInfo, pin: String?): PairingClaim = withContext(Dispatchers.IO) {
        val pinQuery = if (pin != null) "?pin=$pin" else ""
        val req = Request.Builder()
            .url("$migrationBaseUrl/claim$pinQuery")
            .post(P2pJson.encodeToString(ClaimRequest(info = self)).jsonBody())
            .build()
        try {
            clientForLinkIntent.newCall(req).await().use { resp ->
                val claim = when (resp.code) {
                    PrepareRejection.HTTP_UNAUTHORIZED -> PairingClaim.EXPIRED
                    else -> PairingClaim.ACCEPTED
                }
                L.i { "[P2P][Sender] claim code=${resp.code} → $claim" }
                claim
            }
        } catch (e: java.io.IOException) {
            L.w { "[P2P][Sender] claim unreachable: ${e.message}" }
            PairingClaim.UNREACHABLE
        }
    }

    private fun peerState(body: String): LinkPeerState? =
        runCatching { P2pJson.decodeFromString<LinkPeerStateResponse>(body) }
            .getOrNull()
            ?.let { LinkPeerState.of(it.state) }

    /**
     * `GET /status?sessionId=&token=`: how the receiver says this session is doing, or how it ended.
     * Its own connection with a short deadline — the answer comes from the receiver's session
     * bookkeeping, never from the thread doing the import, so it is either quick or not coming.
     * Returns null on a 404 (the receiver does not know the session, or the token is wrong: the
     * wire does not tell them apart on purpose) and throws on transport failure or any other status.
     */
    suspend fun status(sessionId: String, token: String): StatusResponse? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/status?sessionId=$sessionId&token=$token")
            .get()
            .build()
        statusClient.newCall(req).await().use { resp ->
            when {
                resp.code == PrepareRejection.HTTP_NOT_FOUND -> null
                resp.isSuccessful -> P2pJson.decodeFromString<StatusResponse>(resp.body.string())
                else -> error("status failed ${resp.code}")
            }
        }
    }

    /**
     * `cancel`. Best-effort: never throws. [token] is the session token `prepare-upload` returned;
     * a receiver that issued one requires it. Null only for a peer that never issued one (a Desktop
     * pull session), where the request carries the sessionId alone.
     * Returns what the receiver said the cancel did to the data — one of
     * [com.difft.android.p2p.engine.model.CancelState] — or null when it gave no usable answer
     * (unreachable, a non-2xx, an empty body from a receiver that predates the contract).
     */
    suspend fun cancel(sessionId: String, token: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tokenQuery = token?.let { "&token=$it" }.orEmpty()
            val req = Request.Builder()
                .url("$baseUrl/cancel?sessionId=$sessionId$tokenQuery")
                .post(emptyBody())
                .build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body.string()
                if (body.isBlank()) null else P2pJson.decodeFromString<CancelResponse>(body).state
            }
        }.onFailure { e -> L.w { "[P2P][Sender] cancel notice failed: ${e.message}" } }.getOrNull()
    }

    private fun String.jsonBody(): RequestBody =
        toRequestBody("application/json".toMediaType())

    private fun emptyBody(): RequestBody =
        ByteArray(0).toRequestBody("application/octet-stream".toMediaType())

    /**
     * Streaming body over a [FileSource] — no full-file buffering, reports progress.
     *
     * A negative [FileSource.size] means unknown length: [contentLength] returns `-1`, so OkHttp
     * frames the body with `Transfer-Encoding: chunked` (the migration container upload). A
     * non-negative size keeps known `Content-Length` framing. Either way [writeTo] streams the source
     * to EOF through a fixed [SEGMENT] buffer, in constant memory.
     */
    private class StreamingRequestBody(
        private val source: FileSource,
        private val onProgress: (Long, Long) -> Unit,
        private val onBodySent: () -> Unit,
    ) : RequestBody() {
        override fun contentType() = source.mimeType.toMediaType()

        /** `-1` for an unknown-length source → OkHttp uses chunked transfer-encoding. */
        override fun contentLength(): Long = if (source.size < 0L) -1L else source.size

        override fun writeTo(sink: BufferedSink) {
            // 0 total for chunked (indeterminate); real total for Content-Length.
            val total = if (source.size < 0L) 0L else source.size
            var sent = 0L
            source.openRead().buffer().use { src ->
                val buf = okio.Buffer()
                while (true) {
                    val read = src.read(buf, SEGMENT)
                    if (read == -1L) break
                    sink.write(buf, read)
                    sent += read
                    onProgress(sent, total)
                }
            }
            onBodySent()
        }

        companion object {
            private const val SEGMENT = 64 * 1024L
        }
    }

    companion object {
        private const val STATUS_CALL_TIMEOUT_SECONDS = 8L

        /** Login-path calls must not make a sign-in wait for an unreachable LAN. */
        private const val LINK_INTENT_CONNECT_TIMEOUT_SECONDS = 3L
        private const val LINK_INTENT_CALL_TIMEOUT_SECONDS = 5L

        /** How long one body write may wait for the receiver to take bytes before the link counts as gone; see [uploadClient]. */
        private const val UPLOAD_WRITE_STALL_SECONDS = 60L

        /** Upper bound on the receiver's verify → import → promote work; see [uploadClient]. */
        private const val UPLOAD_VERDICT_TIMEOUT_MINUTES = 15L
    }
}

/**
 * Thrown by [SenderHttpClient.prepareUpload] when the receiver rejected the handshake with a typed
 * reason. On a 403 [reason] is the migration gate's wire value (env/account/version mismatch — see
 * [com.difft.android.p2p.engine.upload.MigrationRejectReason]); on 401 / 409 / 400 it is one of
 * [PrepareUploadReasons]. Distinct from a generic transport failure so the old device can name the
 * cause, e.g. "the backup is from a different environment" or "the pairing code expired".
 */
class PrepareUploadRejectedException(val reason: String) :
    Exception("prepare-upload rejected: $reason")

/**
 * Reason tokens for the non-gate `prepare-upload` rejections and for the sender's own connection
 * failure, as they appear in `TransferState.Failed.reason`. The first three are the receiver's JSON
 * `error` values, shared with Desktop; [PEER_UNREACHABLE] is set by the sender when the receiver
 * cannot be reached at all — after a Desktop gave up waiting, that is a refused connection, not a
 * stale PIN.
 */
object PrepareUploadReasons {
    const val PIN_REQUIRED = "pin_required"
    const val TOO_MANY_SESSIONS = "too_many_sessions"
    const val INVALID_BODY = "invalid_body"
    const val PEER_UNREACHABLE = "peer-unreachable"

    /** Sender-side: while waiting for the Desktop's account-switch confirmation, its user cancelled (the PIN was cleared → 401). */
    const val PEER_CANCELLED = "peer-cancelled"

    /** Sender-side: the Desktop never answered `prepare-upload` within the account-switch wait budget. */
    const val PEER_CONFIRM_TIMEOUT = "peer-confirm-timeout"

    /** The peer does not speak this reverse-pairing protocol generation, or has no such endpoint. */
    const val UNSUPPORTED_PROTOCOL = "unsupported-protocol"

    /** Reverse pairing: the computer's user refused the transfer while its response was hanging. */
    const val DESKTOP_CANCELLED = "desktop-cancelled"

    /**
     * Reverse pairing, synthesized here: the computer confirmed but never opened `/prepare-upload`
     * within the window it asked for. No wire answer exists for this — only the missing connection.
     */
    const val DESKTOP_NOT_CONNECTED = "desktop-not-connected"
}
