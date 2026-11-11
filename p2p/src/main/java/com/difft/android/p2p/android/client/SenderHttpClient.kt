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
import com.difft.android.p2p.engine.upload.MigrationRejectReason
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








class SenderHttpClient(
    private val ip: String,
    private val port: Int,
    private val fingerprint: String,
) {

    
    constructor(target: QrPayload) : this(target.ip, target.port, target.fp)

    private val baseUrl = "https://$ip:$port/api/localsend/v2"

    
    private val migrationBaseUrl = "https://$ip:$port/api/migration/v1"

    
    private val defaultClient: OkHttpClient by lazy { PinnedHttpClients.pinned(fingerprint) }

    




    @VisibleForTesting
    internal var clientOverride: OkHttpClient? = null

    private val client: OkHttpClient
        get() = clientOverride ?: defaultClient

    







    private val uploadClient: OkHttpClient by lazy {
        defaultClient.newBuilder()
            .readTimeout(UPLOAD_VERDICT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(UPLOAD_WRITE_STALL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val clientForUpload: OkHttpClient
        get() = clientOverride ?: uploadClient

    
    private val statusClient: OkHttpClient by lazy {
        defaultClient.newBuilder().callTimeout(STATUS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    
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

    



    suspend fun prepareUpload(self: DeviceInfo, file: FileMetadata, pin: String?): PrepareUploadResponse =
        prepareUpload(self, mapOf(file.id to file), pin)

    




    suspend fun prepareUpload(
        self: DeviceInfo,
        file: FileMetadata,
        pin: String?,
        handshake: MigrationHandshake,
    ): PrepareUploadResponse = prepareUpload(self, mapOf(file.id to file), pin, handshake)

    




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

    








    suspend fun upload(
        sessionId: String,
        fileId: String,
        token: String,
        source: FileSource,
        
        
        onBodySent: () -> Unit = {},
        
        
        onReply: (headers: Map<String, String>) -> Unit = {},
        
        onRejected: (reason: String) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val body = StreamingRequestBody(source, onProgress, onBodySent)
        val req = Request.Builder()
            .url("$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token")
            .post(body)
            .build()
        clientForUpload.newCall(req).await().use { resp ->
            L.i { "[P2P][Sender] upload fileId=$fileId result=${resp.code}" }
            if (resp.isSuccessful) {
                onReply(resp.headers.names().associateWith { resp.header(it).orEmpty() })
            } else {
                val bodyText = runCatching { resp.body.string() }.getOrNull().orEmpty()
                typedRejection(resp.code, bodyText)?.let { reason ->
                    L.w { "[P2P][Sender] upload rejected by receiver reason=$reason" }
                    onRejected(reason)
                }
            }
            resp.isSuccessful
        }
    }

    




    private val linkIntentClient: OkHttpClient by lazy {
        defaultClient.newBuilder()
            .connectTimeout(LINK_INTENT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LINK_INTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LINK_INTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val clientForLinkIntent: OkHttpClient
        get() = clientOverride ?: linkIntentClient

    






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

    
    enum class PairingClaim { ACCEPTED, EXPIRED, UNREACHABLE }

    






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

    







    private class StreamingRequestBody(
        private val source: FileSource,
        private val onProgress: (Long, Long) -> Unit,
        private val onBodySent: () -> Unit,
    ) : RequestBody() {
        override fun contentType() = source.mimeType.toMediaType()

        
        override fun contentLength(): Long = if (source.size < 0L) -1L else source.size

        override fun writeTo(sink: BufferedSink) {
            
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
        







        fun typedRejection(code: Int, body: String): String? {
            if (code != PrepareRejection.HTTP_FORBIDDEN) return null
            val token = body.trim()
            return MigrationRejectReason.entries.firstOrNull { it.wire == token }?.wire
        }

        private const val STATUS_CALL_TIMEOUT_SECONDS = 8L

        
        private const val LINK_INTENT_CONNECT_TIMEOUT_SECONDS = 3L
        private const val LINK_INTENT_CALL_TIMEOUT_SECONDS = 5L

        
        private const val UPLOAD_WRITE_STALL_SECONDS = 60L

        
        private const val UPLOAD_VERDICT_TIMEOUT_MINUTES = 15L
    }
}








class PrepareUploadRejectedException(val reason: String) :
    Exception("prepare-upload rejected: $reason")








object PrepareUploadReasons {
    const val PIN_REQUIRED = "pin_required"
    const val TOO_MANY_SESSIONS = "too_many_sessions"
    const val INVALID_BODY = "invalid_body"
    const val PEER_UNREACHABLE = "peer-unreachable"

    
    const val PEER_CANCELLED = "peer-cancelled"

    
    const val PEER_CONFIRM_TIMEOUT = "peer-confirm-timeout"

    
    const val UNSUPPORTED_PROTOCOL = "unsupported-protocol"

    
    const val DESKTOP_CANCELLED = "desktop-cancelled"

    



    const val DESKTOP_NOT_CONNECTED = "desktop-not-connected"
}
