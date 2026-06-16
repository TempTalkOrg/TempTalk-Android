package com.difft.android.network.proxy

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ProxySpki] — the shared SPKI pin helper that both
 * [ProxyPinTrustManager] (signaling outer-TLS) and `ProxyTurnTlsVerifier`
 * (TURN outer-TLS, in :call) delegate to. Robolectric is required because
 * [ProxySpki.pinOf] uses `android.util.Base64`, which returns stub bytes on a
 * bare JVM (same reason as [ProxyPinTrustManagerTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ProxySpkiTest {

    // Reuses the self-signed EC (prime256v1) fixture from ProxyPinTrustManagerTest.
    // SPKI pin = base64(sha256(DER SubjectPublicKeyInfo)) = [VALID_PIN].
    private val certPem = """
        -----BEGIN CERTIFICATE-----
        MIIBkjCCATegAwIBAgIUcdpFDZMPF2Ad94f71Bnw6YZB5X4wCgYIKoZIzj0EAwIw
        HjEcMBoGA1UEAwwTcGludGVzdC5leGFtcGxlLmNvbTAeFw0yNjA2MDExMzMzMTla
        Fw0zNjA1MjkxMzMzMTlaMB4xHDAaBgNVBAMME3BpbnRlc3QuZXhhbXBsZS5jb20w
        WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAScsyRgZJVqfzIE/oHr4icLHwcDhwKY
        NGN4t9y6DLI/oLaUSyHBtXSGDCZxe0bz0PfnQDR74x6hln1WD/FHgKfeo1MwUTAd
        BgNVHQ4EFgQUd/hAlK2r2HC9MG4FAp3AH5mjMKAwHwYDVR0jBBgwFoAUd/hAlK2r
        2HC9MG4FAp3AH5mjMKAwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBG
        AiEAsCLkygkCJIjQueSiBEvMmC0VCobKGdK/d/rLbl5HIRUCIQDMZnsSaXXCC4AY
        chxfaevF8NSqKeVesOsuYNj9EtNVxg==
        -----END CERTIFICATE-----
    """.trimIndent()

    private val validPin = "RxVDtkQK8n5c9R2ionc4RdcywbyoTuSfS8AYJJIsGEA="

    private val cert: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
    }

    // T7 — pinOf produces the canonical SPKI pin (sha256 over publicKey.encoded, NO_WRAP base64).
    @Test
    fun `pinOf computes the canonical SPKI pin`() {
        assertEquals(validPin, ProxySpki.pinOf(cert))
    }

    // T8 — matches: exact match, whitespace tolerance, mismatch, blank guard.
    @Test
    fun `matches returns true for the correct pin`() {
        assertTrue(ProxySpki.matches(cert, validPin))
    }

    @Test
    fun `matches tolerates surrounding whitespace in expected pin`() {
        assertTrue(ProxySpki.matches(cert, "  $validPin  "))
    }

    @Test
    fun `matches returns false for a mismatched pin`() {
        assertFalse(ProxySpki.matches(cert, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    @Test
    fun `matches returns false for a blank expected pin`() {
        assertFalse(ProxySpki.matches(cert, ""))
        assertFalse(ProxySpki.matches(cert, "   "))
    }
}
