package com.difft.android.base.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertValidationFailureDetectorTest {

    @Test
    fun `null throwable is not a cert failure`() {
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(null))
    }

    @Test
    fun `top-level CertificateException is detected`() {
        assertTrue(CertValidationFailureDetector.isCertValidationFailure(CertificateException("bad cert")))
    }

    @Test
    fun `top-level CertPathValidatorException is detected`() {
        assertTrue(CertValidationFailureDetector.isCertValidationFailure(CertPathValidatorException("no path")))
    }

    @Test
    fun `top-level SSLPeerUnverifiedException is detected`() {
        assertTrue(CertValidationFailureDetector.isCertValidationFailure(SSLPeerUnverifiedException("hostname mismatch")))
    }

    @Test
    fun `SSLHandshakeException wrapping a CertificateException cause is detected`() {
        // The common Conscrypt/JSSE shape: handshake exception wraps the real cert cause.
        val handshake = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("Trust anchor for certification path not found."))
        }
        assertTrue(CertValidationFailureDetector.isCertValidationFailure(handshake))
    }

    @Test
    fun `cert failure nested deep in the cause chain is detected`() {
        val deep = IOException(
            "request failed",
            SSLException(
                "ssl error",
                CertPathValidatorException("unable to find valid certification path")
            )
        )
        assertTrue(CertValidationFailureDetector.isCertValidationFailure(deep))
    }

    @Test
    fun `bare SSLHandshakeException without a typed cert cause is not detected (typed-only)`() {
        // Typed-only: a handshake exception whose message mentions certificates but which
        // carries no typed cert exception in its cause chain must NOT trigger the warning.
        assertFalse(
            CertValidationFailureDetector.isCertValidationFailure(
                SSLHandshakeException("Trust anchor for certification path not found.")
            )
        )
        assertFalse(
            CertValidationFailureDetector.isCertValidationFailure(
                SSLHandshakeException("Could not validate certificate")
            )
        )
    }

    @Test
    fun `SSLHandshakeException with unrelated message is not a cert failure`() {
        val bare = SSLHandshakeException("Connection reset by peer")
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(bare))
    }

    @Test
    fun `plain IO and timeout errors are not cert failures`() {
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(IOException("network down")))
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun `generic SSLException without cert cause is not a cert failure`() {
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(SSLException("Connection closed by peer")))
    }

    @Test
    fun `self-referential cause chain terminates without infinite loop`() {
        // A throwable whose cause is itself must not hang the detector.
        val looping = object : Exception("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(looping))
    }

    @Test
    fun `cert failure beyond the cause-depth cap is not detected`() {
        // 16+ wrapper frames before the cert exception: intentionally not reported
        // (guards against pathological chains; real chains are shallow).
        var current: Throwable = CertificateException("deep cert")
        repeat(20) { current = IOException("wrapper", current) }
        assertFalse(CertValidationFailureDetector.isCertValidationFailure(current))
    }
}
