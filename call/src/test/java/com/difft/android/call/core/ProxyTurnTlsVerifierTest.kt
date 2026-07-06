package com.difft.android.call.core

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ProxyTurnTlsVerifier] — the WebRTC TURN-TLS SPKI pin verifier.
 * Robolectric is required because the pin path goes through `ProxySpki` →
 * `android.util.Base64`. The verifier MUST be fail-closed: every error path returns
 * false and never throws (it is invoked from a native JNI callback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProxyTurnTlsVerifierTest {

    // Same self-signed EC (prime256v1) fixture as ProxyPinTrustManagerTest / ProxySpkiTest.
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

    /** Leaf cert DER — exactly what WebRTC native hands [ProxyTurnTlsVerifier.verify]. */
    private val certDer: ByteArray by lazy {
        (CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate)
            .encoded
    }

    // T1 — matching pin → true.
    @Test
    fun `verify accepts leaf DER whose SPKI matches the pin`() {
        assertTrue(ProxyTurnTlsVerifier(validPin).verify(certDer))
    }

    // T2 — mismatched pin → false.
    @Test
    fun `verify rejects a mismatched pin`() {
        assertFalse(ProxyTurnTlsVerifier("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").verify(certDer))
    }

    // T3 — malformed DER → false, no throw.
    @Test
    fun `verify rejects malformed DER without throwing`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(byteArrayOf(1, 2, 3, 4, 5)))
    }

    // T4 — null DER → false, no throw.
    @Test
    fun `verify rejects null certificate`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(null))
    }

    // T5 — empty DER → false, no throw.
    @Test
    fun `verify rejects empty certificate`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(ByteArray(0)))
    }

    // T6 — blank expected pin → false (fail-closed even with a valid leaf).
    @Test
    fun `verify rejects when expected pin is blank`() {
        assertFalse(ProxyTurnTlsVerifier("   ").verify(certDer))
    }
}
