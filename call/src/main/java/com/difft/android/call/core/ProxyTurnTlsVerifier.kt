package com.difft.android.call.core

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.proxy.ProxySpki
import livekit.org.webrtc.SSLCertificateVerifier
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate











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
            
            
            
            L.w { "[Call] TURN TLS verify rejected: parse failure ${t.javaClass.simpleName}" }
            false
        }
    }
}
