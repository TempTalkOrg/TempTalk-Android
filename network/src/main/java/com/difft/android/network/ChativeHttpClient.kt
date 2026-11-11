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
    





    service: ChatService? = null,
    




    private val proxyConfigProvider: ProxyConfigProvider? = null,
    
    authTokenProvider: dagger.Lazy<AuthTokenProvider>? = null,
    
    callTimeoutSeconds: Long = 0,
    




    debugLogBodies: Boolean = true,
) {

    interface AuthProvider {
        fun provideAuth(): String?
    }

    private companion object {
        
        const val FAILOVER_TOTAL_BOUND_MS = 30_000L
    }

    
    
    
    
    private val gson = if (serializeNulls) GsonBuilder().serializeNulls().create() else Gson()

    private val customConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .apply {
            
            
            
            
            
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
                        
                        
                        handleRejection = useHttpClientInterceptor,
                    )
                )
            }
            if (useHttpClientInterceptor) {
                addInterceptor(HttpClientInterceptor(pinnedConnection = useCustomCa))
            }
            if (service != null) {
                
                
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
                addInterceptor(buildDebugLogger(debugLogBodies))
            }
            if (useCustomCa) {
                val officialSSLSocketFactoryCreator = OfficialSSLSocketFactoryCreator(applicationContext)
                val socketFactory = officialSSLSocketFactoryCreator.socketFactory
                val trustManager = officialSSLSocketFactoryCreator.trustManager
                sslSocketFactory(socketFactory, trustManager)
            }
            
            
            proxyConfigProvider?.let { provider ->
                dns(ProxyTunnelDns(provider))
                socketFactory(ProxyTunnelSocketFactory(provider))
            }
            
            
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
        
        
        
        proxyConfigProvider?.registerHttpClient(okHttpClient)
    }

    private val retrofit: Retrofit = Retrofit.Builder()
        .addConverterFactory(ScalarsConverterFactory.create())
        
        
        .addConverterFactory(ServerTimeCaptureConverterFactory(GsonConverterFactory.create(gson)))
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .build()

    val httpService = getService(HttpService::class.java)
    fun <T> getService(service: Class<T>): T = retrofit.create(service)
}









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
