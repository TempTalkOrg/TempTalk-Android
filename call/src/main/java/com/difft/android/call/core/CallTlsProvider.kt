package com.difft.android.call.core

import android.content.Context
import com.difft.android.base.log.lumberjack.L

/**
 * Provides the root CA certificate (PEM) for TLS verification in call connections.
 * The certificate is loaded once from merged assets and cached for the lifetime of this instance.
 *
 * If the asset is missing or unreadable (e.g. corrupted APK, misconfigured flavor), reading
 * returns an empty string instead of throwing, so the connection layer can surface a unified
 * CONNECTED_FAILED state via its existing precondition checks.
 */
class CallTlsProvider(private val context: Context) {

    val trustedCert: String by lazy {
        runCatching {
            context.assets.open(CA_CERT_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { e ->
            L.e(e) { "[Call] CallTlsProvider read $CA_CERT_PATH failed: ${e.message}" }
        }.getOrDefault("")
    }

    companion object {
        private const val CA_CERT_PATH = "chative_ssl_ca.pem"
    }
}
