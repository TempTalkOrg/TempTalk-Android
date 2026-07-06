package com.difft.android.call.core

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.proxy.ProxySpki
import livekit.org.webrtc.SSLCertificateVerifier
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * SPKI pin for the TURN outer-TLS (Mode B coturn, self-signed). Invoked by WebRTC
 * native on the network thread once per TURN-TLS handshake with the peer LEAF cert
 * in DER. Fail-closed: any parse error / mismatch / blank pin returns false and
 * NEVER throws (native cannot unwind a Kotlin exception). Lightweight — a single
 * SHA-256 over the SPKI, no I/O, no blocking.
 *
 * [expectedPin] is captured at construction from ProxyConfig.spkiPinBase64 so the
 * verifier is immutable and thread-confined to the value read when the call started.
 */
internal class ProxyTurnTlsVerifier(private val expectedPin: String) : SSLCertificateVerifier {

    override fun verify(certificate: ByteArray?): Boolean {
        return try {
            val der = certificate
            if (der == null || der.isEmpty()) {
                L.w { "[Call] TURN TLS verify rejected: empty leaf DER" }
                return false
            }
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            val ok = ProxySpki.matches(cert, expectedPin)
            if (!ok) {
                L.w { "[Call] TURN TLS verify rejected: SPKI pin mismatch (derLen=${der.size})" }
            }
            ok
        } catch (t: Throwable) {
            // Catch Throwable, not Exception: ClassCastException (non-X509),
            // CertificateException (bad DER), and any native-callback surprise all
            // fail closed. Must never propagate out of verify().
            L.w { "[Call] TURN TLS verify rejected: parse failure ${t.javaClass.simpleName}" }
            false
        }
    }
}
