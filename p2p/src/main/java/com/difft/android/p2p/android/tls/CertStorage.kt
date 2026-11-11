package com.difft.android.p2p.android.tls

import com.difft.android.base.log.lumberjack.L
import java.io.File









class CertStorage(private val dir: File) {

    private val pemFile: File get() = File(dir, PEM_FILE_NAME)

    
    fun loadPem(): String? {
        val file = pemFile
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { L.w { "[P2P][TLS] cert PEM read failed: ${it.message}" } }
            .getOrNull()
    }

    
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
