package com.difft.android.network

import android.content.Context
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
     * When non-null, all connections from this client are routed through the
     * self-hosted proxy (TLS-in-TLS) whenever the provider reports an active
     * config. Read at connect time, so runtime enable/disable needs no rebuild.
     */
    private val proxyConfigProvider: ProxyConfigProvider? = null
) {

    interface AuthProvider {
        fun provideAuth(): String?
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
            // First in the chain: while the proxy is active, pin the request host
            // to the embedded best host BEFORE header/failover/logging see it, so
            // the runtime-toggled proxy host applies without rebuilding this
            // @Singleton's fixed Retrofit baseUrl. Gated to THIS client's own
            // baseUrl host so absolute-URL CDN requests keep their host/TLS trust.
            // No-op when the proxy is off.
            addInterceptor(ProxyHostInterceptor(baseUrl.toHttpUrlOrNull()?.host))
            if (removeHeader) {
                addInterceptor(NoHeaderInterceptor())
            } else {
                addInterceptor(HeaderInterceptor(authProvider))
            }
            if (useHttpClientInterceptor) {
                addInterceptor(HttpClientInterceptor())
            }
            if (BuildConfig.DEBUG) {
                //如果想使用抓包工具获取接口数据，可以开启这个
//                val manager = TrustAllSSLSocketFactory.TrustAllManager()
//                sslSocketFactory(
//                    TrustAllSSLSocketFactory.create(manager),
//                    manager
//                )
                val loggingInterceptor = HttpLoggingInterceptor()
                loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
                addInterceptor(loggingInterceptor)
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
        .addConverterFactory(GsonConverterFactory.create(gson))
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .build()

    val httpService = getService(HttpService::class.java)
    fun <T> getService(service: Class<T>): T = retrofit.create(service)
}