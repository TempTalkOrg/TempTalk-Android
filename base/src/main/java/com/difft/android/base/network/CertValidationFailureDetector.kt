package com.difft.android.base.network

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException



















object CertValidationFailureDetector {

    
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
