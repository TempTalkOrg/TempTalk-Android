package com.difft.android.base.security

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals













@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkSafetyInspectorTest {

    private fun risk(url: String): LinkRisk = LinkSafetyInspector.inspect(url).risk

    

    @Test
    fun `plain ascii domain is safe`() {
        assertEquals(LinkRisk.SAFE, risk("https://example.com"))
    }

    @Test
    fun `ascii domain with path and query is safe`() {
        assertEquals(LinkRisk.SAFE, risk("https://github.com/difftim/TempTalk-Android?tab=readme"))
    }

    @Test
    fun `ascii typosquatting is out of scope and stays safe`() {
        
        assertEquals(LinkRisk.SAFE, risk("https://paypa1.com"))
    }

    @Test
    fun `blank or hostless input is safe`() {
        assertEquals(LinkRisk.SAFE, risk(""))
        assertEquals(LinkRisk.SAFE, risk("not a url"))
        assertEquals(LinkRisk.SAFE, risk("mailto:someone@example.com"))
    }

    

    @Test
    fun `mixed script latin plus cyrillic is homograph`() {
        
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://\u0430pple.com"))
    }

    @Test
    fun `mixed script latin plus greek is homograph`() {
        
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://g\u03bf\u03bfgle.com"))
    }

    @Test
    fun `confusable label mixed with legit cjk label is still flagged`() {
        
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://例え.\u0430pple.com"))
    }

    

    @Test
    fun `whole script cyrillic imitating latin is flagged as non ascii host`() {
        
        
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://\u0430\u0440\u0440\u04cf\u0435.com"))
    }

    @Test
    fun `punycode literal decoding to non ascii is flagged`() {
        
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://xn--80ak6aa92e.com"))
    }

    @Test
    fun `punycode literal decoding to mixed script is homograph`() {
        
        
        
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://xn--pple-43d.com"))
    }

    @Test
    fun `punycode display host is decoded to unicode`() {
        
        val verdict = LinkSafetyInspector.inspect("https://xn--80ak6aa92e.com")
        assertEquals("\u0430\u0440\u0440\u04cf\u0435.com", verdict.displayHost)
        assertEquals("xn--80ak6aa92e.com", verdict.realAsciiHost)
    }

    @Test
    fun `legit japanese idn is flagged for confirmation`() {
        
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://例え.jp"))
    }

    @Test
    fun `legit german idn with diacritics is flagged for confirmation`() {
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://bücher.de"))
    }

    

    @Test
    fun `userinfo obfuscation is deceptive`() {
        
        assertEquals(LinkRisk.DECEPTIVE_USERINFO, risk("https://apple.com@evil.com"))
    }

    

    @Test
    fun `verdict reveals punycode real host for a decoded idn`() {
        val verdict = LinkSafetyInspector.inspect("https://xn--80ak6aa92e.com")
        assertEquals(LinkRisk.NON_ASCII_HOST, verdict.risk)
        assertEquals("xn--80ak6aa92e.com", verdict.realAsciiHost)
    }

    @Test
    fun `safe display url rewrites unicode host to punycode`() {
        
        val verdict = LinkSafetyInspector.inspect("https://\u0430pple.com/login?x=1")
        assertEquals("https://xn--pple-43d.com/login?x=1", verdict.safeDisplayUrl)
    }

    @Test
    fun `safe display url drops deceptive userinfo prefix`() {
        
        val verdict = LinkSafetyInspector.inspect("https://apple.com@evil.com/pay")
        assertEquals("https://evil.com/pay", verdict.safeDisplayUrl)
    }
}
