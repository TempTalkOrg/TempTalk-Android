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














class ReversePairClient(
    private val ip: String,
    private val port: Int,
    private val fingerprint: String,
) {

    
    constructor(target: QrPayload) : this(target.ip, target.port, target.fp)

    
    private val migrationBaseUrl = "https://$ip:$port/api/migration/v1"

    private val defaultClient: OkHttpClient by lazy {
        PinnedHttpClients.pinned(fingerprint).newBuilder()
            .callTimeout(HANG_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .readTimeout(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            
            
            .retryOnConnectionFailure(false)
            .build()
    }

    




    @VisibleForTesting
    internal var clientOverride: OkHttpClient? = null

    @VisibleForTesting
    internal val client: OkHttpClient
        get() = clientOverride ?: defaultClient

    






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
        
        const val HANG_TIMEOUT_MINUTES = 5L

        
        const val IDLE_TIMEOUT_SECONDS = 30L

        
        private const val HTTP_REQUEST_TIMEOUT = 408

        private const val JSON_MEDIA_TYPE = "application/json"
    }
}
