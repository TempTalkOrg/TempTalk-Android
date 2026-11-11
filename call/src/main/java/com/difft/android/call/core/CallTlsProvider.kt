package com.difft.android.call.core

import android.content.Context
import com.difft.android.base.log.lumberjack.L









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
