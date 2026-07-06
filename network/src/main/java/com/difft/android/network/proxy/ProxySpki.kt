package com.difft.android.network.proxy

import android.util.Base64
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Single owner of the SPKI-pin algorithm shared by the signaling-tunnel
 * [ProxyPinTrustManager] and the call-media TURN-TLS verifier. The pin is
 * base64(SHA-256(DER SubjectPublicKeyInfo)) of a leaf cert's public key,
 * compared in constant time against the share-code pin.
 */
object ProxySpki {

    /** base64(SHA-256(cert.publicKey.encoded)) using [Base64.NO_WRAP]. */
    fun pinOf(cert: X509Certificate): String {
        // Fresh MessageDigest per call: like CertificateFactory, MessageDigest is
        // NOT documented thread-safe, and pinOf can run on the WebRTC network thread
        // (via the TURN verifier) concurrently with the OkHttp tunnel thread (via the
        // trust manager). Allocation is microsecond-scale and not a hot loop — do NOT
        // cache a shared instance to "optimize".
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * True iff [cert]'s SPKI pin constant-time-equals [expectedPin] (trimmed).
     * Returns false on blank expected pin (fail-closed for the caller).
     */
    fun matches(cert: X509Certificate, expectedPin: String): Boolean {
        val expected = expectedPin.trim()
        if (expected.isEmpty()) return false
        return constantTimeEquals(pinOf(cert), expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }
}
