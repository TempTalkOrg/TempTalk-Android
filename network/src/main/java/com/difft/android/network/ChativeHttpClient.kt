package com.difft.android.network

import android.content.Context
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.ca.OfficialSSLSocketFactoryCreator
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyTunnelDns
import com.difft.android.network.proxy.ProxyTunnelSocketFactory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit


class ChativeHttpClient(
    private val applicationContext: Context,
    baseUrl: String,
    authProvider: AuthProvider?,
    useCustomCa: Boolean = true,
    removeHeader: Boolean = false,
    connectTimeoutSeconds: Long = 15,
    readWriteTimeoutSeconds: Long = 15,
    useHttpClientInterceptor: Boolean = true,
    serializeNulls: Boolean = false,
    /**
     * The chat-pool service this client talks to, or null for a client outside the pool (it then
     * gets no host routing and no failover). Requests to this client's own origin host are routed
     * per request and failed over across the pool (#1396); [baseUrl] stays only as the origin
     * marker, so it must be that service's URL.
     */
    service: ChatService? = null,
    /**
     * When non-null, all connections from this client are routed through the
     * self-hosted proxy (TLS-in-TLS) whenever the provider reports an active
     * config. Read at connect time, so runtime enable/disable needs no rebuild.
     */
    private val proxyConfigProvider: ProxyConfigProvider? = null,
    /** JWT source for token-authenticated endpoints. Lazy so the graph stays acyclic (issue #1231). */
    authTokenProvider: dagger.Lazy<AuthTokenProvider>? = null,
    /** 0 = no call timeout, matching OkHttp's default and today's behaviour on every client. */
    callTimeoutSeconds: Long = 0,
    /**
     * DEBUG log level. false logs headers only — for clients whose request body carries a
     * credential (the fileshare token travels in the body), which [HttpLoggingInterceptor]
     * cannot redact. Issue #1231.
     */
    debugLogBodies: Boolean = true,
) {

    interface AuthProvider {
        fun provideAuth(): String?
    }

    private companion object {
        /** Failover bound for a client with no call timeout of its own. */
        const val FAILOVER_TOTAL_BOUND_MS = 30_000L
    }

    // Retrofit converter Gson — intentionally NOT the chat-domain singleton:
    // constructed once per @Singleton ChativeHttpClient (no GC pressure), network DTOs
    // need no For/ByteString adapters, and serializeNulls=true (signalApi protocol-defensive,
    // Match Jackson's default null-field serialization) must not leak globally.
    private val gson = if (serializeNulls) GsonBuilder().serializeNulls().create() else Gson()

    private val customConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3) // 指定TLS版本为TLS 1.2  TLS 1.2
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .apply {
            // First in the chain: resolve the request host (and service path) per request, BEFORE
            // header/failover/logging see it, so a host marked unavailable, a live config change
            // or the runtime-toggled proxy applies without rebuilding this @Singleton's fixed
            // Retrofit baseUrl. Gated to THIS client's own baseUrl host so absolute-URL CDN
            // requests keep their host/TLS trust.
            val origin = baseUrl.toHttpUrlOrNull()
            if (service != null && origin != null) {
                addInterceptor(ChatPoolRoutingInterceptor(origin, service))
            }
            if (removeHeader) {
                addInterceptor(NoHeaderInterceptor())
            } else {
                addInterceptor(
                    HeaderInterceptor(
                        authProvider = authProvider,
                        tokenProvider = authTokenProvider,
                        // Clients without HttpClientInterceptor hand raw statuses to their
                        // repositories, which map 401/403 themselves.
                        handleRejection = useHttpClientInterceptor,
                    )
                )
            }
            if (useHttpClientInterceptor) {
                addInterceptor(HttpClientInterceptor(pinnedConnection = useCustomCa))
            }
            if (service != null) {
                // Closest to the network, inside normalization, so it classifies raw responses and
                // exceptions. Raw-status clients keep their 5xx: marked, never resent.
                addInterceptor(
                    HostFailoverInterceptor(
                        resendOnServerError = useHttpClientInterceptor,
                        pinnedConnection = useCustomCa,
                        totalBoundMs = if (callTimeoutSeconds > 0) {
                            TimeUnit.SECONDS.toMillis(callTimeoutSeconds)
                        } else {
                            FAILOVER_TOTAL_BOUND_MS
                        },
                    )
                )
                addNetworkInterceptor(ExchangeObserver())
            }
            if (BuildConfig.DEBUG) {
                //如果想使用抓包工具获取接口数据，可以开启这个
//                val manager = TrustAllSSLSocketFactory.TrustAllManager()
//                sslSocketFactory(
//                    TrustAllSSLSocketFactory.create(manager),
//                    manager
//                )
                addInterceptor(buildDebugLogger(debugLogBodies))
            }
            if (useCustomCa) {
                val officialSSLSocketFactoryCreator = OfficialSSLSocketFactoryCreator(applicationContext)
                val socketFactory = officialSSLSocketFactoryCreator.socketFactory
                val trustManager = officialSSLSocketFactoryCreator.trustManager
                sslSocketFactory(socketFactory, trustManager)
            }
            // Outer TLS-in-TLS tunnel. No-op while the proxy is disabled (plain
            // socket + system DNS), so the inner chative TLS above is unchanged.
            proxyConfigProvider?.let { provider ->
                dns(ProxyTunnelDns(provider))
                socketFactory(ProxyTunnelSocketFactory(provider))
            }
            // Bounds the whole call, not just one socket phase: a client awaited from inside
            // another client's interceptor must not be able to block that worker indefinitely.
            if (callTimeoutSeconds > 0) {
                callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            }
        }
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readWriteTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(readWriteTimeoutSeconds, TimeUnit.SECONDS)
        .connectionSpecs(listOf(customConnectionSpec))
        .build()
    init {
        // Register so a runtime proxy enable/disable evicts THIS client's connection
        // pool too — otherwise OkHttp keep-alive reuses the stale tunnel/direct socket
        // and requests keep flowing through the old route until it dies on its own.
        proxyConfigProvider?.registerHttpClient(okHttpClient)
    }

    private val retrofit: Retrofit = Retrofit.Builder()
        .addConverterFactory(ScalarsConverterFactory.create())
        // Wraps the Gson factory to capture BaseResponse.serverTimestamp for ServerTimeProvider.
        // One hook covers all ChativeHttpClient instances; scalar conversion above is unaffected.
        .addConverterFactory(ServerTimeCaptureConverterFactory(GsonConverterFactory.create(gson)))
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .build()

    val httpService = getService(HttpService::class.java)
    fun <T> getService(service: Class<T>): T = retrofit.create(service)
}

/**
 * The DEBUG-build request logger. Debug logs are what gets attached to internal bug reports, and
 * `Authorization` carries either the password-equivalent Basic credential or the account JWT, so it
 * is always redacted (case-insensitively). [debugLogBodies] false drops to HEADERS for clients
 * whose request body carries a credential, which [HttpLoggingInterceptor] cannot redact.
 *
 * Extracted from the builder above so the redaction itself is testable. Issue #1231.
 */
internal fun buildDebugLogger(
    debugLogBodies: Boolean,
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
    setLevel(
        if (debugLogBodies) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.HEADERS
    )
    redactHeader(HeaderInterceptor.HEADER_KEY_AUTHORIZATION)
}