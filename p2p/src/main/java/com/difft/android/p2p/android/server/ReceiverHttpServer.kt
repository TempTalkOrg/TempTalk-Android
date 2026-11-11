package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.model.CancelResponse
import com.difft.android.p2p.engine.model.ClaimRequest
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import com.difft.android.p2p.engine.upload.CancelHandler
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.PrepareUploadHandler
import com.difft.android.p2p.engine.upload.StatusHandler
import com.difft.android.p2p.engine.upload.StoragePrecheck
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
















class ReceiverHttpServer(
    private val cert: SelfSignedCert,
    deviceInfo: DeviceInfo,
    private val sessionManager: SessionManager,
    private val pinPolicy: PinPolicy,
    private val fileSink: FileSink,
    private val requirePin: Boolean,
    private val onEvent: (ServerEvent) -> Unit = {},
    




    migrationValidator: MigrationHandshakeValidator? = null,
    
    private val idleReadTimeoutMs: Int = IDLE_READ_TIMEOUT_MS,
    



    private val verdictHeartbeatIntervalMs: Long = InterimResponseHeartbeat.INTERVAL_MS,
    



    private val pairingRegistry: PairingSessionRegistry? = null,
    
    storagePrecheck: StoragePrecheck? = null,
    
    caps: List<String> = ReceiverCaps.BASE,
) {
    





    @Volatile
    var deviceInfo: DeviceInfo = deviceInfo
        private set
    
    sealed interface ServerEvent {
        data class Started(val port: Int, val fingerprint: String) : ServerEvent
        
        data class IncomingPrepared(
            val sessionId: String,
            val fileCount: Int,
            val peer: DeviceInfo,
            
            val totalBytes: Long = 0L,
        ) : ServerEvent
        data class FileReceived(val result: UploadResult) : ServerEvent
        
        data class PeerRegistered(val peer: DeviceInfo) : ServerEvent
        
        data class Claimed(val peer: DeviceInfo) : ServerEvent
        
        data class UploadStarted(val sessionId: String, val fileId: String) : ServerEvent
        
        data class PeerCancelled(val sessionId: String) : ServerEvent
        
        data class Error(val detail: String, val cause: Throwable? = null) : ServerEvent
        data object Stopped : ServerEvent
    }

    private val prepareHandler = PrepareUploadHandler(
        sessionManager = sessionManager,
        pinPolicy = pinPolicy,
        requirePin = requirePin,
        migrationValidator = migrationValidator,
        pairingRegistry = pairingRegistry,
        storagePrecheck = storagePrecheck,
        caps = caps,
    )
    private val uploadHandler = UploadHandler(sessionManager, fileSink)
    private val cancelHandler = CancelHandler(sessionManager)
    private val statusHandler = StatusHandler(sessionManager)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    
    internal val boundAddress: java.net.InetAddress?
        get() = serverSocket?.inetAddress
    @Volatile private var acceptJob: Job? = null
    



    private val connections: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var stopped = false
    
    @Volatile private var acceptingSessions = true

    







    fun start(port: Int = DEFAULT_PORT, bindAddress: java.net.InetAddress? = null): Int {
        
        
        
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
        
        
        if (deviceInfo.port != boundPort) deviceInfo = deviceInfo.copy(port = boundPort)
        L.i { "[P2P][Receiver] started port=$boundPort fp=${cert.sha256Fingerprint.take(12)}" }
        onEvent(ServerEvent.Started(boundPort, cert.sha256Fingerprint))

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                connections += client
                
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

    




    fun closeToNewSessions() {
        acceptingSessions = false
    }

    
    fun retireLiveSessionForLinger(): Boolean = sessionManager.retireLiveForLinger()

    





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

    




    private fun handleUpload(head: HttpRequestHead, source: okio.BufferedSource, sink: okio.BufferedSink, sourceIp: String) {
        val sessionId = head.query["sessionId"] ?: throw ProtocolError.BadRequest("sessionId")
        val fileId = head.query["fileId"] ?: throw ProtocolError.BadRequest("fileId")
        val token = head.query["token"] ?: throw ProtocolError.BadRequest("token")
        val body = bodySource(head, source, fileId)
        
        
        
        val heartbeat = InterimResponseHeartbeat(sink, verdictHeartbeatIntervalMs)
        
        
        
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
                    
                    
                    pairingRegistry?.markUploading()
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
            if (e is TransferCommittedUnconfirmed) {
                
                
                
                L.w { "[MigrationV2][P2P][Rx] committed but unconfirmed — no response, no terminal" }
                sessionManager.markUnconfirmed(sessionId)
                throw e
            }
            
            if (authorized) sessionManager.markTerminal(sessionId, TransferTerminal.FAILED)
            throw e
        } finally {
            heartbeat.stop()
        }
        
        
        
        if (sessionManager.isComplete(sessionId)) sessionManager.markTerminal(sessionId, TransferTerminal.SUCCEEDED)
        
        
        
        writeStatus(sink, 200, result.headers)
        sink.flush()
        onEvent(ServerEvent.FileReceived(result))
        L.i { "[P2P][Receiver] file done id=$fileId bytes=${result.bytes} sha=${result.sha256.take(12)}" }
    }

    private fun handleConnection(client: Socket) {
        val sourceIp = client.inetAddress?.hostAddress ?: "unknown"
        
        
        
        L.i {
            "[P2P][Receiver] conn peer=$sourceIp local=${client.localAddress?.hostAddress}:${client.localPort}"
        }
        
        
        
        
        runCatching { client.soTimeout = idleReadTimeoutMs }
        try {
            val source = client.source().buffer()
            val sink = client.sink().buffer()
            
            
            
            
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
                
                
                head.path.endsWith("/info") -> {
                    drainBody(source, head.requireControlBody())
                    writeJson(sink, 200, P2pJson.encodeToString(deviceInfo))
                }

                head.path.endsWith("/register") -> {
                    
                    
                    val bodyBytes = readControlBody(head, source)
                    runCatching { P2pJson.decodeFromString<DeviceInfo>(bodyBytes.decodeToString()) }
                        .getOrNull()
                        ?.takeIf { it.fingerprint != deviceInfo.fingerprint } 
                        ?.let { onEvent(ServerEvent.PeerRegistered(it.copy(lastSeenIp = sourceIp))) }
                    writeJson(sink, 200, P2pJson.encodeToString(deviceInfo))
                }

                
                
                
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

                
                
                
                head.path.endsWith("/status") -> {
                    drainBody(source, head.requireControlBody())
                    val status = statusHandler.handle(head.query["sessionId"], head.query["token"])
                    writeJson(sink, 200, P2pJson.encodeToString(status))
                }

                head.path.endsWith("/prepare-upload") -> {
                    
                    
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

    
    private fun reportConnectionFailure(sourceIp: String, e: Exception) {
        if (stopped) {
            L.w { "[P2P][Receiver] connection from=$sourceIp dropped by stop: ${e.javaClass.simpleName}" }
            return
        }
        L.e { "[P2P][Receiver] connection failed from=$sourceIp: ${e.stackTraceToString()}" }
        onEvent(ServerEvent.Error(e.message ?: e.javaClass.simpleName, e))
    }

    



    private fun bodySource(head: HttpRequestHead, source: okio.BufferedSource, fileId: String): okio.Source =
        if (head.isChunked) {
            L.i { "[P2P][Receiver] upload fileId=$fileId framing=chunked" }
            ChunkedSource(source)
        } else {
            LimitedSource(source, head.contentLength)
        }

    
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

        






        const val IDLE_READ_TIMEOUT_MS = 30_000
    }
}
