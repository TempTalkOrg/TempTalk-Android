package com.difft.android.p2p.android.tls

import com.difft.android.base.log.lumberjack.L
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory










class SelfSignedCert private constructor(
    val held: HeldCertificate,
    val sha256Fingerprint: String,
) {

    
    fun sslServerSocketFactory(): SSLServerSocketFactory {
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(handshake.keyManager), arrayOf(handshake.trustManager), null)
        return ctx.serverSocketFactory
    }

    companion object {
        



        const val CERT_COMMON_NAME = "tt-p2p"

        




        fun loadOrCreate(storage: CertStorage, localIp: String? = null): SelfSignedCert {
            storage.loadPem()?.let { pem ->
                runCatching { HeldCertificate.decode(pem) }
                    .onFailure { L.w { "[P2P][TLS] stored cert decode failed, regenerating: ${it.message}" } }
                    .getOrNull()
                    ?.let { return fromHeld(it) }
            }
            val held = HeldCertificate.Builder()
                .commonName(CERT_COMMON_NAME)
                .apply { localIp?.let { addSubjectAlternativeName(it) } }
                .build()
            storage.savePem(held.certificatePem() + held.privateKeyPkcs8Pem())
            L.i { "[P2P][TLS] generated new self-signed cert" }
            return fromHeld(held)
        }

        private fun fromHeld(held: HeldCertificate): SelfSignedCert {
            val fp = FingerprintTrustManager.certSha256Hex(held.certificate)
            return SelfSignedCert(held, fp)
        }
    }
}
