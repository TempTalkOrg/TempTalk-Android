package com.difft.android.network.proxy

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProxyConfigTest {

    private val full = ProxyConfig(
        host = "203.0.113.7",
        port = 8443,
        spkiPinBase64 = "AbC123+/=",
        sni = "cdn.example.com",
        turnSecret = "deadbeef",
    )

    @Test
    fun `plain link round-trips through encode and parse`() {
        val link = full.toShareLink()
        assertTrue(link.startsWith("ytp://config?d="))

        val parsed = ProxyConfig.parse(link)
        requireNotNull(parsed)
        assertEquals("203.0.113.7", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("AbC123+/=", parsed.spkiPinBase64)
        assertEquals("cdn.example.com", parsed.sni)
        assertEquals("deadbeef", parsed.turnSecret)
    }

    @Test
    fun `defaults port to 443 and no turn`() {
        val link = ProxyLinkCodec.encodePlain(
            ProxyConfig(host = "h", port = 443, spkiPinBase64 = "pin")
        )
        val parsed = ProxyConfig.parse(link)
        requireNotNull(parsed)
        assertEquals(443, parsed.port)
        assertNull(parsed.turnSecret)
    }

    @Test
    fun `encrypted link decodes only with the correct passphrase`() {
        val link = ProxyLinkCodec.encodeEncrypted(full, "river-amber-pencil")

        // Plain parser refuses an encrypted link.
        assertNull(ProxyConfig.parse(link))
        assertEquals(ProxyLinkCodec.Mode.ENCRYPTED, ProxyLinkCodec.inspect(link))

        val wrong = ProxyLinkCodec.decodeEncrypted(link, "nope")
        assertEquals(ProxyLinkCodec.Decoded.WrongPassphrase, wrong)

        val ok = ProxyLinkCodec.decodeEncrypted(link, "river-amber-pencil")
        assertTrue(ok is ProxyLinkCodec.Decoded.Success)
        assertEquals("203.0.113.7", (ok as ProxyLinkCodec.Decoded.Success).config.host)
        assertEquals("deadbeef", ok.config.turnSecret)
    }

    @Test
    fun `inspect reports plain mode`() {
        assertEquals(ProxyLinkCodec.Mode.PLAIN, ProxyLinkCodec.inspect(full.toShareLink()))
    }

    @Test
    fun `rejects wrong scheme and authority`() {
        assertNull(ProxyConfig.parse("https://config?d=AQA"))
        assertNull(ProxyConfig.parse("ytp://server?d=AQA"))
        assertNull(ProxyLinkCodec.inspect("ytp://server?d=AQA"))
    }

    @Test
    fun `rejects garbage and malformed payloads`() {
        assertNull(ProxyConfig.parse("not a uri at all"))
        assertNull(ProxyConfig.parse("ytp://config"))
        assertNull(ProxyConfig.parse("ytp://config?d=%%%notbase64%%%"))
        assertNull(ProxyLinkCodec.inspect("not a uri at all"))
    }

    @Test
    fun `outerSni falls back to decoy hostname when sni is null`() {
        // H-2: ProxyConnectivityChecker.check() now uses config.outerSni() instead
        // of config.host (an IP literal), so the probe matches TlsTunnelSocket's
        // real handshake and avoids IllegalArgumentException from SNIHostName.
        val cfg = ProxyConfig(host = "1.2.3.4", port = 443, spkiPinBase64 = "x", sni = null)
        assertEquals("www.bing.com", cfg.outerSni())
    }

    @Test
    fun `toString masks turnSecret and spkiPin`() {
        // L-1: the auto-generated data-class toString() would otherwise expose
        // the coturn static-auth-secret and full SPKI pin to any future log
        // statement that accidentally interpolates the config object.
        val rendered = full.toString()
        assertTrue("deadbeef" !in rendered, "turnSecret literal must not appear: $rendered")
        assertTrue("<set>" in rendered, "must mark turn as <set>: $rendered")
        // Pin prefix kept for diagnostic disambiguation; full value masked.
        assertTrue("AbC123+/" in rendered, "pin prefix should be visible: $rendered")
        assertTrue("AbC123+/=" !in rendered, "trailing pin chars must be masked: $rendered")
        // Non-secret fields still rendered.
        assertTrue("203.0.113.7" in rendered, "host should be visible: $rendered")
        assertTrue("8443" in rendered, "port should be visible: $rendered")
    }

    @Test
    fun `toString marks turn as none when secret absent`() {
        val cfg = ProxyConfig(host = "h", port = 443, spkiPinBase64 = "x")
        val rendered = cfg.toString()
        assertTrue("<none>" in rendered, "must mark turn as <none>: $rendered")
    }

    @Test
    fun `outerSni uses explicit sni when provided`() {
        val cfg = ProxyConfig(
            host = "1.2.3.4",
            port = 443,
            spkiPinBase64 = "x",
            sni = "custom.example.com",
        )
        assertEquals("custom.example.com", cfg.outerSni())
    }

    @Test
    fun `quicEnabled round-trips through encode and parse`() {
        // q is the runtime gate for the whole QUIC-over-proxy feature; guard both
        // the encode (put "q",1) and parse (optInt "q") branches against regression.
        val cfg = ProxyConfig(host = "1.2.3.4", port = 443, spkiPinBase64 = "pin", quicEnabled = true)
        val parsed = ProxyConfig.parse(cfg.toShareLink())
        requireNotNull(parsed)
        assertTrue(parsed.quicEnabled)
    }

    @Test
    fun `q field absent defaults to quicEnabled false`() {
        val cfg = ProxyConfig(host = "1.2.3.4", port = 443, spkiPinBase64 = "pin")
        val parsed = ProxyConfig.parse(cfg.toShareLink())
        requireNotNull(parsed)
        assertFalse(parsed.quicEnabled)
    }

    @Test
    fun `explicit q=1 in wire payload parses to quicEnabled true`() {
        // Pin the WIRE contract directly (literal "q":1), not just an encode→parse
        // round-trip — a symmetric bug on both ends could otherwise cancel out.
        val json = """{"v":1,"h":"203.0.113.7","p":443,"f":"pin","q":1}"""
            .toByteArray(Charsets.UTF_8)
        val blob = byteArrayOf(0x01, 0x00) + json
        val d = android.util.Base64.encodeToString(
            blob, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        val parsed = ProxyConfig.parse("ytp://config?d=$d")
        requireNotNull(parsed)
        assertTrue(parsed.quicEnabled)
    }

    @Test
    fun `rejects payload missing required fields`() {
        // Hand-craft a plain envelope whose JSON lacks the required fingerprint.
        val json = """{"v":1,"h":"203.0.113.7","p":443}""".toByteArray(Charsets.UTF_8)
        val blob = byteArrayOf(0x01, 0x00) + json
        val d = android.util.Base64.encodeToString(
            blob, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        assertNull(ProxyConfig.parse("ytp://config?d=$d"))
    }
}
