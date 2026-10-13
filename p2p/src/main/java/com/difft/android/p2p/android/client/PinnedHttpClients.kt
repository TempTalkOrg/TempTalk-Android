package com.difft.android.p2p.android.client

import com.difft.android.p2p.android.tls.FingerprintTrustManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

/**
 * The one place a client pinned to a peer's certificate fingerprint is built.
 *
 * Every migration client talks to a raw LAN IP behind a self-signed certificate, so trust comes from
 * the fingerprint the QR carried and nothing else: [FingerprintTrustManager] accepts that one
 * certificate, and `hostnameVerifier { _, _ -> true }` is deliberate because an IP can never match
 * the self-signed CN. A second copy of this block in another client would be a second place the pin
 * could be weakened by accident, so callers take the built client and re-time it with `newBuilder()`.
 */
internal object PinnedHttpClients {

    /**
     * A client whose TLS trusts [fingerprint] alone, with the control-call deadlines: a LAN peer
     * answers in milliseconds, so 5 s to connect already covers a Wi-Fi power-save wake. Callers
     * needing another shape call `newBuilder()` on the result, which keeps the pinned socket factory
     * and shares the connection pool.
     */
    fun pinned(fingerprint: String): OkHttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // see kdoc — the pin is the trust, not the hostname
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Control calls carry no body worth timing; a body-carrying call sets its own.
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 60L
}
