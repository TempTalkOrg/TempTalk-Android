package com.difft.android.p2p.android.tls

import com.difft.android.base.log.lumberjack.L
import java.io.File

/**
 * Persists the self-signed cert + private key as PEM across app sessions so the receiver's
 * fingerprint is a stable identity anchor. The key lives in a module-private PEM file and is not
 * additionally AndroidKeyStore-wrapped.
 *
 * Stored as a two-document PEM: okhttp-tls `HeldCertificate.certificatePem()` followed by
 * `privateKeyPkcs8Pem()`, which `HeldCertificate.decode()` round-trips.
 */
class CertStorage(private val dir: File) {

    private val pemFile: File get() = File(dir, PEM_FILE_NAME)

    /** Reads the persisted PEM, or null if absent/unreadable. */
    fun loadPem(): String? {
        val file = pemFile
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { L.w { "[P2P][TLS] cert PEM read failed: ${it.message}" } }
            .getOrNull()
    }

    /** Writes [pem] (cert + PKCS8 key) to module-private storage. */
    fun savePem(pem: String) {
        runCatching {
            dir.mkdirs()
            pemFile.writeText(pem)
        }.onFailure { L.w { "[P2P][TLS] cert PEM write failed: ${it.message}" } }
    }

    companion object {
        private const val PEM_FILE_NAME = "p2p_self_signed_cert.pem"
    }
}
