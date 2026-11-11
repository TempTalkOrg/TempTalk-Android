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







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProxyTurnTlsVerifierTest {

    
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

    
    private val certDer: ByteArray by lazy {
        (CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate)
            .encoded
    }

    
    @Test
    fun `verify accepts leaf DER whose SPKI matches the pin`() {
        assertTrue(ProxyTurnTlsVerifier(validPin).verify(certDer))
    }

    
    @Test
    fun `verify rejects a mismatched pin`() {
        assertFalse(ProxyTurnTlsVerifier("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").verify(certDer))
    }

    
    @Test
    fun `verify rejects malformed DER without throwing`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(byteArrayOf(1, 2, 3, 4, 5)))
    }

    
    @Test
    fun `verify rejects null certificate`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(null))
    }

    
    @Test
    fun `verify rejects empty certificate`() {
        assertFalse(ProxyTurnTlsVerifier(validPin).verify(ByteArray(0)))
    }

    
    @Test
    fun `verify rejects when expected pin is blank`() {
        assertFalse(ProxyTurnTlsVerifier("   ").verify(certDer))
    }
}
