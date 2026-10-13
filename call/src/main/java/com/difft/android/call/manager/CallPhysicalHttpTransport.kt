package com.difft.android.call.manager

import android.content.Context
import com.difft.android.base.call.ServiceUrlDataV2
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.call.core.CallTlsProvider
import com.difft.android.call.core.physicalNetworkHandle
import com.difft.android.network.BaseResponse
import com.difft.android.network.UrlManager
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.config.UserAgentManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.difft.android.smp.HttpCallback
import org.difft.android.smp.HttpConfig
import org.difft.android.smp.HttpConnector
import org.difft.android.smp.HttpRequest
import org.difft.android.smp.HttpResponse
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CallPhysicalHttpTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val urlManager: UrlManager,
    private val authTokenProvider: AuthTokenProvider,
) {
    private data class ConnectorState(
        val networkHandle: Long,
        val connector: HttpConnector,
    )

    private val lock = Any()
    private val gson = Gson()
    private val trustedCert by lazy { CallTlsProvider(context).trustedCert }
    private var connectorState: ConnectorState? = null

    suspend fun fetchServiceUrls(): BaseResponse<ServiceUrlDataV2> {
        val networkHandle = context.physicalNetworkHandle()
        check(networkHandle != 0L) { "No non-VPN network is available" }
        val connector = connectorFor(networkHandle)
        val token = authTokenProvider.current()
        val request = HttpRequest().apply {
            method = "GET"
            url = "${urlManager.call}v3/call/serviceurl/v2"
            timeoutMs = REQUEST_TIMEOUT_MS
            headers = buildMap {
                if (token.isNotEmpty()) {
                    put("Authorization", token)
                }
                put("User-Agent", UserAgentManager.getUserAgent())
                put("Accept-Language", Locale.getDefault().language)
            }
        }
        val response = connector.await(request)
        check(response.status in 200..299) {
            "Physical HTTP returned status ${response.status}"
        }
        L.i {
            "[Call] physical service URL request succeeded " +
                "boundIfIndex=${response.boundIfIndex} pinMethod=${response.pinMethod}"
        }
        val type = TypeToken.getParameterized(
            BaseResponse::class.java,
            ServiceUrlDataV2::class.java,
        ).type
        val result: BaseResponse<ServiceUrlDataV2> =
            gson.fromJson(String(response.body, Charsets.UTF_8), type)
        result.serverTimestamp
            ?.takeIf { it > 0L }
            ?.let { ServerTimeProvider.update(it, "call-physical-http") }
        return result
    }

    fun close() {
        val connector = synchronized(lock) {
            connectorState?.connector.also { connectorState = null }
        }
        connector?.close()
    }

    private fun connectorFor(networkHandle: Long): HttpConnector = synchronized(lock) {
        connectorState?.takeIf { it.networkHandle == networkHandle }?.connector
            ?: HttpConnector(
                HttpConfig().apply {
                    vpnPolicy = "force-physical"
                    caCerts = trustedCert
                    androidNetHandle = networkHandle
                    drainTimeoutMs = CONNECTOR_DRAIN_TIMEOUT_MS
                }
            ).also { created ->
                connectorState?.connector?.close()
                connectorState = ConnectorState(networkHandle, created)
            }
    }

    private suspend fun HttpConnector.await(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            request(
                request,
                object : HttpCallback {
                    override fun onResponse(response: HttpResponse) {
                        continuation.resume(response)
                    }

                    override fun onError(result: Int, errorName: String, errorMessage: String) {
                        continuation.resumeWithException(
                            CallPhysicalHttpException(result, errorName, errorMessage),
                        )
                    }
                }
            )
        }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 8_000
        private const val CONNECTOR_DRAIN_TIMEOUT_MS = 5_000
    }
}

private class CallPhysicalHttpException(
    val result: Int,
    val errorName: String,
    message: String,
) : Exception("$errorName($result): $message")
