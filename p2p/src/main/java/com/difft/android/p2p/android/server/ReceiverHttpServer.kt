package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.model.CancelResponse
import com.difft.android.p2p.engine.model.ClaimRequest
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import com.difft.android.p2p.engine.upload.CancelHandler
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.PrepareUploadHandler
import com.difft.android.p2p.engine.upload.StatusHandler
import com.difft.android.p2p.engine.upload.UploadHandler
import com.difft.android.p2p.engine.upload.UploadResult
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okio.buffer
import okio.sink
import okio.source

/**
 * The receiver's HTTPS server. Runs an [SSLServerSocket] (cert from [SelfSignedCert]) accept loop on
 * [Dispatchers.IO]; each connection is handled on a child coroutine under a [SupervisorJob] so one
 * bad connection cannot kill the server.
 *
 * Routes the discovery + upload-flow endpoints to the transport-agnostic engine handlers:
 *  - `GET  /api/localsend/v{1,2}/info`      → 200 + our [DeviceInfo] (LocalSend probes /v1 as fallback)
 *  - `POST /api/localsend/v2/register`      → echoes our [DeviceInfo] and surfaces the caller as a peer
 *  - `POST /api/localsend/v2/prepare-upload?pin=` → [PrepareUploadHandler]
 *  - `POST /api/localsend/v2/upload?sessionId=&fileId=&token=` → [UploadHandler] (streamed)
 *  - `POST /api/localsend/v2/cancel?sessionId=` → [CancelHandler]
 *
 * The upload body streams from the socket straight into the [FileSink], so a large file is never
 * buffered whole.
 */
class ReceiverHttpServer(
    private val cert: SelfSignedCert,
    deviceInfo: DeviceInfo,
    private val sessionManager: SessionManager,
    private val pinPolicy: PinPolicy,
    private val fileSink: FileSink,
    private val requirePin: Boolean,
    private val onEvent: (ServerEvent) -> Unit = {},
    /**
     * The migration pre-transfer gate — non-null only when this server is stood up for a migration
     * receive (env isolation + account + version fail-fast at `prepare-upload`), null for generic
     * file transfer. Threaded straight into [PrepareUploadHandler].
     */
    migrationValidator: MigrationHandshakeValidator? = null,
    /** See [IDLE_READ_TIMEOUT_MS]. Overridable so a test can trip it without waiting 30 seconds. */
    private val idleReadTimeoutMs: Int = IDLE_READ_TIMEOUT_MS,
    /**
     * How often the `/upload` connection emits `102 Processing` while the verdict is pending — see
     * [InterimResponseHeartbeat]. Overridable so a test can observe several beats in well under a second.
     */
    private val verdictHeartbeatIntervalMs: Long = InterimResponseHeartbeat.INTERVAL_MS,
) {
    /**
     * Our advertised DeviceInfo, served from `/info` + `/register`. Mutable because the bound port is
     * only known after [start] binds (a busy 53317 falls back to an ephemeral port), and announce /
     * register / the QR must advertise where we actually listen. `@Volatile`: read on connection
     * coroutines, written once on the start thread.
     */
    @Volatile
    var deviceInfo: DeviceInfo = deviceInfo
        private set
    /** Observable server lifecycle / transfer events for the owner (no Android types here). */
    sealed interface ServerEvent {
        data class Started(val port: Int, val fingerprint: String) : ServerEvent
        /** A sender's prepare-upload was accepted; [peer] is its self-description (`info`), alias included. */
        data class IncomingPrepared(
            val sessionId: String,
            val fileCount: Int,
            val peer: DeviceInfo,
            /** Sum of the declared file sizes: the sender's advisory total the progress reading is measured against (0 = unknown). */
            val totalBytes: Long = 0L,
        ) : ServerEvent
        data class FileReceived(val result: UploadResult) : ServerEvent
        /** A peer reached us via `POST /register`; surfaces it to the peer list. */
        data class PeerRegistered(val peer: DeviceInfo) : ServerEvent
        /** A scanner read the live code and claimed its PIN; the code must stop rotating. */
        data class Claimed(val peer: DeviceInfo) : ServerEvent
        /** The sender's `/upload` passed authorization: its body is about to flow. */
        data class UploadStarted(val sessionId: String, val fileId: String) : ServerEvent
        /** The sender named its session on `POST /cancel`: it left on purpose. */
        data class PeerCancelled(val sessionId: String) : ServerEvent
        /** [cause] is the connection failure itself when there was one (a socket that ended, a read that timed out). */
        data class Error(val detail: String, val cause: Throwable? = null) : ServerEvent
        data object Stopped : ServerEvent
    }

    private val prepareHandler = PrepareUploadHandler(sessionManager, pinPolicy, requirePin, migrationValidator)
    private val uploadHandler = UploadHandler(sessionManager, fileSink)
    private val cancelHandler = CancelHandler(sessionManager)
    private val statusHandler = StatusHandler(sessionManager)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    /** The address the listening socket is bound to — loopback-only for a same-device receiver. */
    internal val boundAddress: java.net.InetAddress?
        get() = serverSocket?.inetAddress
    @Volatile private var acceptJob: Job? = null
    /**
     * Accepted sockets still being served. Each is a blocking read on an IO thread, which cancelling
     * its coroutine cannot unblock — closing the socket is what ends it, so [stop] needs the list.
     */
    private val connections: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var stopped = false
    /** False once the receiver is only kept up for `/status` and `/cancel` — see [closeToNewSessions]. */
    @Volatile private var acceptingSessions = true

    /**
     * Starts listening on [port], restricted to [bindAddress] when one is given. Returns the actual
     * bound port (53317 default).
     *
     * A same-device receiver advertises only loopback, so it listens only there and is unreachable
     * from the LAN. A null [bindAddress] keeps the wildcard bind a cross-device receiver needs for
     * the several addresses it advertises.
     */
    fun start(port: Int = DEFAULT_PORT, bindAddress: java.net.InetAddress? = null): Int {
        // Created unbound so SO_REUSEADDR can be set before binding (avoids EADDRINUSE on a port left
        // in TIME_WAIT by a just-stopped receiver). A genuinely occupied fixed port falls back to an
        // ephemeral one — the QR carries the actual bound port.
        val socket = cert.sslServerSocketFactory().createServerSocket().apply {
            reuseAddress = true
            try {
                bind(java.net.InetSocketAddress(bindAddress, port))
            } catch (e: java.io.IOException) {
                L.w { "[P2P][Receiver] port $port busy (${e.message}); binding ephemeral port" }
                bind(java.net.InetSocketAddress(bindAddress, 0))
            }
        }
        serverSocket = socket
        val boundPort = socket.localPort
        // Advertise the actual bound port so /info, /register replies, announce and the QR all point
        // at where we listen.
        if (deviceInfo.port != boundPort) deviceInfo = deviceInfo.copy(port = boundPort)
        L.i { "[P2P][Receiver] started port=$boundPort fp=${cert.sha256Fingerprint.take(12)}" }
        onEvent(ServerEvent.Started(boundPort, cert.sha256Fingerprint))

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                connections += client
                // Registered first, then re-checked: a stop between the two would otherwise miss it.
                if (stopped) {
                    connections -= client
                    runCatching { client.close() }
                    break
                }
                scope.launch { handleConnection(client) }
            }
        }
        return boundPort
    }

    /**
     * Keep listening, but only to answer for the session that already ran: `prepare-upload` is refused
     * from here on. The owner calls this when it leaves the listener up after a finished transfer so
     * the sender can still learn how it ended.
     */
    fun closeToNewSessions() {
        acceptingSessions = false
    }

    /**
     * Stops the server, tears down sessions and drops in-flight connections. A body still being read
     * ends here with the sender's write failing, which is how the sender learns this side stopped.
     * Connections dying under the close report nothing — a failure would overwrite the owner's own
     * cancelled state.
     */
    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        sessionManager.clear()
        pinPolicy.reset()
        scope.cancel()
        L.i { "[P2P][Receiver] stopped" }
        onEvent(ServerEvent.Stopped)
    }

    /**
     * `POST /upload`: the body is the transfer, the response is the verdict. Everything the receiver
     * tells the sender about how the session went — stage moves, the terminal — happens here, on the
     * one exchange both ends are guaranteed to share.
     */
    private fun handleUpload(head: HttpRequestHead, source: okio.BufferedSource, sink: okio.BufferedSink, sourceIp: String) {
        val sessionId = head.query["sessionId"] ?: throw ProtocolError.BadRequest("sessionId")
        val fileId = head.query["fileId"] ?: throw ProtocolError.BadRequest("fileId")
        val token = head.query["token"] ?: throw ProtocolError.BadRequest("token")
        val body = bodySource(head, source, fileId)
        // Interim 102s keep the sender's idle clock from reading the verdict's silence as a dead
        // receiver. They start only once the body is in, and stop — beat in flight joined — before
        // anything final touches the sink, error path included, so no interim can interleave.
        val heartbeat = InterimResponseHeartbeat(sink, verdictHeartbeatIntervalMs)
        // Set once this request is the session's genuine upload. A request refused before that (wrong
        // file token, wrong source IP) must not end the session, or anyone who learned a sessionId
        // could kill the live transfer with one bad request.
        var authorized = false
        val result = try {
            uploadHandler.handle(
                sessionId = sessionId,
                fileId = fileId,
                token = token,
                sourceIp = sourceIp,
                body = body,
                onAuthorized = {
                    authorized = true
                    sessionManager.stage(TransferStages.RECEIVING)
                    onEvent(ServerEvent.UploadStarted(sessionId, fileId))
                },
                onBodyComplete = {
                    L.i { "[P2P][Receiver] body complete id=$fileId — awaiting verdict, heartbeat on" }
                    sessionManager.stage(TransferStages.VERIFYING)
                    heartbeat.start()
                },
            )
        } catch (e: Exception) {
            // A session the sender already cancelled is retained as such; this is a no-op then.
            if (authorized) sessionManager.markTerminal(sessionId, TransferTerminal.FAILED)
            throw e
        } finally {
            heartbeat.stop()
        }
        // Terminal before the verdict is written: if the 200 is lost on the way, the very next
        // /status must already say `succeeded`. Only once every file of the session has landed —
        // the protocol shape allows several.
        if (sessionManager.isComplete(sessionId)) sessionManager.markTerminal(sessionId, TransferTerminal.SUCCEEDED)
        // The verdict goes on the wire before the event is published, so a consumer acting on
        // FileReceived (the same-device handoff restarts the app on it) can rely on the 200 being
        // written and flushed.
        writeStatus(sink, 200, result.headers)
        sink.flush()
        onEvent(ServerEvent.FileReceived(result))
        L.i { "[P2P][Receiver] file done id=$fileId bytes=${result.bytes} sha=${result.sha256.take(12)}" }
    }

    private fun handleConnection(client: Socket) {
        val sourceIp = client.inetAddress?.hostAddress ?: "unknown"
        // Both ends of the accepted socket, once per connection: a peer outside our own subnet was
        // routed through a tunnel and left the LAN, the usual reason a same-room transfer crawls,
        // and the local address says which interface it landed on.
        L.i {
            "[P2P][Receiver] conn peer=$sourceIp local=${client.localAddress?.hostAddress}:${client.localPort}"
        }
        // Bounds every read on this connection. A peer that stops sending without closing the socket
        // (an OS-suspended iOS sender) would otherwise block the body read forever: `UploadHandler`
        // never returns, the receive pipe never closes and the UI sits on a frozen progress bar.
        // SO_TIMEOUT measures the gap between bytes, so a slow-but-alive link never trips it.
        runCatching { client.soTimeout = idleReadTimeoutMs }
        try {
            val source = client.source().buffer()
            val sink = client.sink().buffer()
            // A connection the peer closes before any request is not a transfer failing: the
            // connecting side races every advertised address and closes the losers mid-handshake, and
            // a pinned client that rejected our certificate hangs up the same way. Surfacing either
            // would fail a receive that has not started. A connected-but-silent peer keeps its report.
            val head = try {
                HttpRequestParser.parseHead(source)
            } catch (e: java.net.SocketTimeoutException) {
                throw e
            } catch (e: java.io.IOException) {
                L.i { "[P2P][Receiver] connection from=$sourceIp closed before a request: ${e.javaClass.simpleName}" }
                return
            }
            L.i { "[P2P][Receiver] req ${head.method} ${head.path} from=$sourceIp" }

            when {
                // LocalSend probes /info (GET) as a discovery fallback, including /v1/info. Both
                // /v1/info and /v2/info answer with our DeviceInfo.
                head.path.endsWith("/info") -> {
                    drainBody(source, head.requireControlBody())
                    writeJson(sink, 200, P2pJson.encodeToString(deviceInfo))
                }

                head.path.endsWith("/register") -> {
                    // Echo our DeviceInfo back and parse the caller's to populate our peer list.
                    // The parse is best-effort: a malformed or empty body still gets our reply.
                    val bodyBytes = readControlBody(head, source)
                    runCatching { P2pJson.decodeFromString<DeviceInfo>(bodyBytes.decodeToString()) }
                        .getOrNull()
                        ?.takeIf { it.fingerprint != deviceInfo.fingerprint } // ignore self-loopback
                        ?.let { onEvent(ServerEvent.PeerRegistered(it.copy(lastSeenIp = sourceIp))) }
                    writeJson(sink, 200, P2pJson.encodeToString(deviceInfo))
                }

                // A scanner claims the live PIN the moment it reads the code, so the code stops
                // rotating while its user reads the confirmation page. Nothing is consumed here; a
                // stale code is refused now instead of minutes later at prepare-upload.
                head.path.endsWith("/claim") -> {
                    val bodyBytes = readControlBody(head, source)
                    val peer = runCatching { P2pJson.decodeFromString<ClaimRequest>(bodyBytes.decodeToString()).info }.getOrNull()
                    if (requirePin && !pinPolicy.matches(head.query["pin"])) {
                        L.w { "[P2P][Receiver] claim pin rejected from=$sourceIp" }
                        throw ProtocolError.Unauthorized
                    }
                    pinPolicy.touch()
                    peer?.let { onEvent(ServerEvent.Claimed(it.copy(lastSeenIp = sourceIp))) }
                    writeStatus(sink, 200)
                }

                // The sender asking after its upload on a connection of its own — while the upload
                // is in flight, and after it is over, for as long as the receiver is up. Answered from
                // the session manager alone, so a blocked import thread cannot silence it.
                head.path.endsWith("/status") -> {
                    drainBody(source, head.requireControlBody())
                    val status = statusHandler.handle(head.query["sessionId"], head.query["token"])
                    writeJson(sink, 200, P2pJson.encodeToString(status))
                }

                head.path.endsWith("/prepare-upload") -> {
                    // Bounded before the refusal path, so a closed receiver cannot be made to drain
                    // an oversized length either.
                    val bodyLength = head.requireControlBody()
                    if (!acceptingSessions) {
                        drainBody(source, bodyLength)
                        L.w { "[P2P][Receiver] prepare-upload refused: receiver is closed to new sessions" }
                        throw ProtocolError.Conflict
                    }
                    val bodyBytes = source.readByteArray(bodyLength)
                    val req = P2pJson.decodeFromString<PrepareUploadRequest>(bodyBytes.decodeToString())
                    val resp = prepareHandler.handle(req, sourceIp, head.query["pin"])
                    onEvent(ServerEvent.IncomingPrepared(resp.sessionId, resp.files.size, req.info, req.files.values.sumOf { it.size }))
                    writeJson(sink, 200, P2pJson.encodeToString(resp))
                }

                head.path.endsWith("/upload") -> handleUpload(head, source, sink, sourceIp)

                head.path.endsWith("/cancel") -> {
                    drainBody(source, head.requireControlBody())
                    val sessionId = head.query["sessionId"]
                    val outcome = cancelHandler.handle(sessionId, head.query["token"])
                    // Only a cancel that ended a live session is the peer leaving. One that arrived
                    // past the commit point changed nothing — the import goes on and so must the owner.
                    if (outcome.tornDown && sessionId != null) onEvent(ServerEvent.PeerCancelled(sessionId))
                    writeJson(sink, 200, P2pJson.encodeToString(CancelResponse(outcome.state)))
                }

                else -> {
                    drainBody(source, head.requireControlBody())
                    writeStatus(sink, 404)
                }
            }
            sink.flush()
        } catch (e: ProtocolError) {
            L.w { "[P2P][Receiver] protocol error status=${e.httpStatus}: ${e.message}" }
            // Write the typed detail as the response body so the sender can surface the specific
            // reason, in particular the migration gate's env/account/version reject. For a generic
            // file-transfer error this is just the short ProtocolError message.
            runCatching {
                client.sink().buffer().also { writeText(it, e.httpStatus, e.detailBody()); it.flush() }
            }
            if (!stopped) onEvent(ServerEvent.Error("${e.httpStatus} ${e.message}"))
        } catch (e: Exception) {
            reportConnectionFailure(sourceIp, e)
        } finally {
            connections -= client
            runCatching { client.close() }
        }
    }

    /** A connection dying under [stop] is the stop itself, not a failure of the transfer. */
    private fun reportConnectionFailure(sourceIp: String, e: Exception) {
        if (stopped) {
            L.w { "[P2P][Receiver] connection from=$sourceIp dropped by stop: ${e.javaClass.simpleName}" }
            return
        }
        L.e { "[P2P][Receiver] connection failed from=$sourceIp: ${e.stackTraceToString()}" }
        onEvent(ServerEvent.Error(e.message ?: e.javaClass.simpleName, e))
    }

    /**
     * Body framing: chunked (the migration container, unknown length — de-chunked on read) or
     * `Content-Length` (generic file transfer — bounded read). Both are accepted.
     */
    private fun bodySource(head: HttpRequestHead, source: okio.BufferedSource, fileId: String): okio.Source =
        if (head.isChunked) {
            L.i { "[P2P][Receiver] upload fileId=$fileId framing=chunked" }
            ChunkedSource(source)
        } else {
            LimitedSource(source, head.contentLength)
        }

    /** Buffers a control body whole, bounded by [HttpRequestHead.requireControlBody]. */
    private fun readControlBody(head: HttpRequestHead, source: okio.BufferedSource): ByteArray {
        val length = head.requireControlBody()
        return if (length > 0) source.readByteArray(length) else ByteArray(0)
    }

    private fun drainBody(source: okio.BufferedSource, length: Long) {
        if (length > 0) source.skip(length)
    }

    private fun writeStatus(sink: okio.BufferedSink, status: Int, headers: Map<String, String> = emptyMap()) {
        sink.writeUtf8("HTTP/1.1 $status ${reason(status)}\r\n")
        sink.writeUtf8("Content-Length: 0\r\n")
        headers.forEach { (name, value) -> sink.writeUtf8("$name: $value\r\n") }
        sink.writeUtf8("Connection: close\r\n\r\n")
    }

    /**
     * Write a short text/plain body alongside the status — used for error responses so the sender can
     * read the typed reason (the migration gate's reject reason in particular). Empty [text] degrades to
     * a header-only response (Content-Length: 0), identical to [writeStatus].
     */
    private fun writeText(sink: okio.BufferedSink, status: Int, text: String) {
        if (text.isEmpty()) {
            writeStatus(sink, status)
            return
        }
        val bytes = text.encodeToByteArray()
        sink.writeUtf8("HTTP/1.1 $status ${reason(status)}\r\n")
        sink.writeUtf8("Content-Type: text/plain\r\n")
        sink.writeUtf8("Content-Length: ${bytes.size}\r\n")
        sink.writeUtf8("Connection: close\r\n\r\n")
        sink.write(bytes)
    }

    private fun writeJson(sink: okio.BufferedSink, status: Int, json: String) {
        val bytes = json.encodeToByteArray()
        sink.writeUtf8("HTTP/1.1 $status ${reason(status)}\r\n")
        sink.writeUtf8("Content-Type: application/json\r\n")
        sink.writeUtf8("Content-Length: ${bytes.size}\r\n")
        sink.writeUtf8("Connection: close\r\n\r\n")
        sink.write(bytes)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    companion object {
        const val DEFAULT_PORT = 53317

        /**
         * How long a connection may go without delivering a single byte before the receiver treats
         * the peer as gone. Applies between bytes, not to the transfer as a whole, so it is
         * independent of transfer size and link speed. 30s sits above the longest legitimate silence
         * (a GC pause, a slow DB page, a Wi-Fi roam) and above iOS's background-execution grace
         * period, and below the point where a stuck screen reads as a hang.
         */
        const val IDLE_READ_TIMEOUT_MS = 30_000
    }
}
