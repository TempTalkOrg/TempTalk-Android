package com.difft.android.p2p.android.tls

import com.difft.android.base.log.lumberjack.L
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Sender-side pin-only [X509TrustManager]: accepts only a server whose leaf certificate's whole-DER
 * SHA-256 equals the fingerprint carried in the scanned QR (`fp`). This replaces CA validation — the
 * self-signed receiver cert has no chain, so trust is "is this the device the QR pointed at?".
 *
 * Shaped like `network/.../proxy/ProxyPinTrustManager`, but deliberately hashes the whole cert DER
 * (the LocalSend convention) rather than the SPKI that `ProxySpki.pinOf` hashes — reusing `ProxySpki`
 * would break LocalSend wire compatibility.
 */
internal class FingerprintTrustManager(expectedFpHex: String) : X509TrustManager {

    private val expectedFp = expectedFpHex.trim().lowercase()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("[P2P][TLS] empty cert chain")
        val actual = certSha256Hex(leaf)
        if (!constantTimeEquals(actual, expectedFp)) {
            // A mismatch is usually whole-DER vs SPKI hashing on the other platform. Truncated
            // fingerprints only, even though a cert digest is public wire data.
            L.w { "[P2P][TLS] fingerprint mismatch exp=${expectedFp.take(12)} act=${actual.take(12)}" }
            throw CertificateException("[P2P][TLS] fingerprint mismatch exp=${expectedFp.take(12)} act=${actual.take(12)}")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }

    companion object {
        /** Lowercase hex SHA-256 of the whole DER-encoded cert — the LocalSend fingerprint. */
        fun certSha256Hex(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}
