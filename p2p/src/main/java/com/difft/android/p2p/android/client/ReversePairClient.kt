package com.difft.android.p2p.android.client

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReversePairRequest
import com.difft.android.p2p.engine.model.ReversePairResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `POST /api/migration/v1/reverse-pair?pin=` — the one request the phone makes as a client while it
 * is the receiving server.
 *
 * The peer holds the response open while its user confirms the transfer, so this call has its own
 * deadlines and must not reuse [SenderHttpClient]'s upload client: that one waits 15 minutes for an
 * import verdict, which is the wrong shape for a confirmation hang, and its write deadline is a body
 * stall watchdog this request has no body for. Total budget 5 minutes (`callTimeout`); a 30 s sliding
 * idle deadline (`readTimeout`) that OkHttp restarts on every `102 Processing` the peer beats.
 *
 * Cancelling the calling coroutine closes the socket at once (see `Call.await`), which is how the
 * peer learns the phone left during the hang.
 */
class ReversePairClient(
    private val ip: String,
    private val port: Int,
    private val fingerprint: String,
) {

    /** Pairing constructor: pulls {ip, port, fp} from the scanned payload. */
    constructor(target: QrPayload) : this(target.ip, target.port, target.fp)

    /** Our own extension endpoints live under a separate prefix so upstream LocalSend additions cannot collide. */
    private val migrationBaseUrl = "https://$ip:$port/api/migration/v1"

    private val defaultClient: OkHttpClient by lazy {
        PinnedHttpClients.pinned(fingerprint).newBuilder()
            .callTimeout(HANG_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .readTimeout(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // OkHttp otherwise replays the request once on a 408, and a 408 here means the peer's own
            // confirmation timer expired: it has already voided the code, so the replay would hang for
            // another five minutes and then report a stale PIN instead of "not confirmed".
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Test-only override: lets a test inject a mocked client whose `newCall` returns a controllable
     * [okhttp3.Call], so the cancel bridge can be driven without a real socket. Never set in
     * production. Mirrors [SenderHttpClient.clientOverride].
     */
    @VisibleForTesting
    internal var clientOverride: OkHttpClient? = null

    @VisibleForTesting
    internal val client: OkHttpClient
        get() = clientOverride ?: defaultClient

    /**
     * Sends the pairing request and suspends until the peer's user confirms. Every non-2xx becomes a
     * [PrepareUploadRejectedException] carrying a wire reason token, so the screen can name the cause:
     * the shared 400/401/403/409 mapping, plus two this endpoint answers alone — a 404 from a peer
     * that predates the endpoint, which is an upgrade prompt rather than a retry, and a 408 from the
     * peer's own confirmation timer, which the user resolves by scanning again.
     */
    suspend fun reversePair(body: ReversePairRequest, pin: String): ReversePairResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$migrationBaseUrl/reverse-pair?pin=$pin")
                .post(P2pJson.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()
            L.i { "[MigrationV2][P2P][Rx] reverse-pair awaiting confirm peer=$ip:$port" }
            client.newCall(request).await().use { resp ->
                when (resp.code) {
                    PrepareRejection.HTTP_NOT_FOUND -> throw PrepareUploadRejectedException(PrepareUploadReasons.UNSUPPORTED_PROTOCOL)
                    HTTP_REQUEST_TIMEOUT -> throw PrepareUploadRejectedException(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT)
                }
                if (!resp.isSuccessful) PrepareRejection.throwRejection("reverse-pair", resp)
                val confirmed = P2pJson.decodeFromString<ReversePairResponse>(resp.body.string())
                L.i {
                    "[MigrationV2][P2P][Rx] reverse-pair confirmed sessionId=${confirmed.sessionId} " +
                        "connectWithinMs=${confirmed.connectWithinMs}"
                }
                confirmed
            }
        }

    companion object {
        /** Total budget for the confirmation hang; the peer's own timer expires first with a 408. */
        const val HANG_TIMEOUT_MINUTES = 5L

        /** Sliding idle deadline, restarted by every `102 Processing` the peer beats. */
        const val IDLE_TIMEOUT_SECONDS = 30L

        /** The peer gave up waiting for its user; it has voided the code and the phone re-scans. */
        private const val HTTP_REQUEST_TIMEOUT = 408

        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
