package com.difft.android.network.proxy

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class ProxyPinTrustManagerTest {

    // Self-signed EC (prime256v1) cert generated for this test.
    // Its SPKI pin (base64(sha256(DER SubjectPublicKeyInfo))) is [VALID_PIN].
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

    @Test
    fun `accepts certificate whose SPKI matches the pin`() {
        ProxyPinTrustManager(validPin).checkServerTrusted(arrayOf(cert), "EC")
    }

    @Test
    fun `tolerates surrounding whitespace in pin`() {
        ProxyPinTrustManager("  $validPin  ").checkServerTrusted(arrayOf(cert), "EC")
    }

    @Test
    fun `rejects certificate with mismatched pin`() {
        val wrongPin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertFailsWith<CertificateException> {
            ProxyPinTrustManager(wrongPin).checkServerTrusted(arrayOf(cert), "EC")
        }
    }

    @Test
    fun `rejects empty certificate chain`() {
        assertFailsWith<CertificateException> {
            ProxyPinTrustManager(validPin).checkServerTrusted(emptyArray(), "EC")
        }
    }

    @Test
    fun `rejects null certificate chain`() {
        assertFailsWith<CertificateException> {
            ProxyPinTrustManager(validPin).checkServerTrusted(null, "EC")
        }
    }

    @Test
    fun `accepted issuers is empty (no CA validation)`() {
        assert(ProxyPinTrustManager(validPin).acceptedIssuers.isEmpty())
    }
}
