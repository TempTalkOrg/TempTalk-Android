package com.difft.android.p2p.android.client

import com.difft.android.p2p.android.tls.FingerprintTrustManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient










internal object PinnedHttpClients {

    





    fun pinned(fingerprint: String): OkHttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } 
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 60L
}
