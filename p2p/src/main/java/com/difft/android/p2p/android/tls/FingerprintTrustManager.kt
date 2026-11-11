package com.difft.android.p2p.android.tls

import com.difft.android.base.log.lumberjack.L
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager










internal class FingerprintTrustManager(expectedFpHex: String) : X509TrustManager {

    private val expectedFp = expectedFpHex.trim().lowercase()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("[P2P][TLS] empty cert chain")
        val actual = certSha256Hex(leaf)
        if (!constantTimeEquals(actual, expectedFp)) {
            
            
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
        
        fun certSha256Hex(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}
