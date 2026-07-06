package com.difft.android.base.network

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Decides whether a [Throwable] represents a TLS certificate validation failure
 * (the signal we treat as a possible man-in-the-middle attack on a pinned channel).
 *
 * TLS handshake failures surface as nested exceptions: OkHttp/JSSE usually wraps the
 * real cause ([CertificateException] / [CertPathValidatorException]) inside an
 * SSLHandshakeException. We therefore walk the whole cause chain instead of only
 * inspecting the top-level throwable.
 *
 * Typed-only by design: detection relies solely on the strongly-typed certificate
 * exceptions in the cause chain, never on an SSLHandshakeException's free-text message.
 * On Android/Conscrypt the real cause is always one of those typed exceptions, so this
 * keeps detection accurate while avoiding false positives from unrelated handshake
 * errors whose message happens to contain words like "certificate".
 *
 * Pure function with no Android/network dependencies so it can be reused across the
 * HTTP, WebSocket and call layers and unit-tested in isolation.
 */
object CertValidationFailureDetector {

    // Guards against pathological / self-referential cause chains.
    private const val MAX_CAUSE_DEPTH = 16

    fun isCertValidationFailure(throwable: Throwable?): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        val visited = HashSet<Throwable>()

        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            when (current) {
                is CertificateException,
                is CertPathValidatorException,
                is SSLPeerUnverifiedException -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
