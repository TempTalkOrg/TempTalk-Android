package com.difft.android.base.security

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Contract tests for [LinkSafetyInspector].
 *
 * Runs under Robolectric only for a working `android.net.Uri`; the detection
 * itself is pure JDK ([java.net.IDN] + [Character.UnicodeScript]).
 *
 * Classification rules under test:
 * - Latin + non-CJK script mixing in one label  -> HOMOGRAPH (impersonation).
 * - Any other non-ASCII host                    -> NON_ASCII_HOST (verify).
 * - userinfo@host                               -> DECEPTIVE_USERINFO.
 * - Plain ASCII                                 -> SAFE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkSafetyInspectorTest {

    private fun risk(url: String): LinkRisk = LinkSafetyInspector.inspect(url).risk

    // ---- SAFE: plain ASCII ------------------------------------------------

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
        // paypa1.com is pure ASCII — not a Unicode homograph (documented gap).
        assertEquals(LinkRisk.SAFE, risk("https://paypa1.com"))
    }

    @Test
    fun `blank or hostless input is safe`() {
        assertEquals(LinkRisk.SAFE, risk(""))
        assertEquals(LinkRisk.SAFE, risk("not a url"))
        assertEquals(LinkRisk.SAFE, risk("mailto:someone@example.com"))
    }

    // ---- HOMOGRAPH: disallowed script mixing ------------------------------

    @Test
    fun `mixed script latin plus cyrillic is homograph`() {
        // "аpple" — Cyrillic 'а' (U+0430) + Latin.
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://\u0430pple.com"))
    }

    @Test
    fun `mixed script latin plus greek is homograph`() {
        // "gοοgle" — Greek omicron (U+03BF) among Latin.
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://g\u03bf\u03bfgle.com"))
    }

    @Test
    fun `confusable label mixed with legit cjk label is still flagged`() {
        // 例え.аpple.com — CJK label is fine, but the "аpple" label mixes Latin+Cyrillic.
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://例え.\u0430pple.com"))
    }

    // ---- NON_ASCII_HOST: non-ASCII without disallowed mixing --------------

    @Test
    fun `whole script cyrillic imitating latin is flagged as non ascii host`() {
        // "аррӏе" — all Cyrillic; single-script, so not classified HOMOGRAPH,
        // but still surfaced for confirmation.
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://\u0430\u0440\u0440\u04cf\u0435.com"))
    }

    @Test
    fun `punycode literal decoding to non ascii is flagged`() {
        // xn--80ak6aa92e.com decodes to the Cyrillic "аррӏе".
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://xn--80ak6aa92e.com"))
    }

    @Test
    fun `punycode literal decoding to mixed script is homograph`() {
        // xn--pple-43d.com decodes to "аpple.com" (Latin + Cyrillic). The manual
        // Punycode fallback lets us detect the mixed script even when the platform
        // IDN.toUnicode declines to decode.
        assertEquals(LinkRisk.HOMOGRAPH, risk("https://xn--pple-43d.com"))
    }

    @Test
    fun `punycode display host is decoded to unicode`() {
        // The dialog must be able to reveal the human-readable look-alike form.
        val verdict = LinkSafetyInspector.inspect("https://xn--80ak6aa92e.com")
        assertEquals("\u0430\u0440\u0440\u04cf\u0435.com", verdict.displayHost)
        assertEquals("xn--80ak6aa92e.com", verdict.realAsciiHost)
    }

    @Test
    fun `legit japanese idn is flagged for confirmation`() {
        // 例え.jp — legitimate, but without confusable data we confirm before opening.
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://例え.jp"))
    }

    @Test
    fun `legit german idn with diacritics is flagged for confirmation`() {
        assertEquals(LinkRisk.NON_ASCII_HOST, risk("https://bücher.de"))
    }

    // ---- DECEPTIVE_USERINFO ----------------------------------------------

    @Test
    fun `userinfo obfuscation is deceptive`() {
        // Browser navigates to evil.com; the "apple.com" prefix is just user-info.
        assertEquals(LinkRisk.DECEPTIVE_USERINFO, risk("https://apple.com@evil.com"))
    }

    // ---- verdict exposes real destination --------------------------------

    @Test
    fun `verdict reveals punycode real host for a decoded idn`() {
        val verdict = LinkSafetyInspector.inspect("https://xn--80ak6aa92e.com")
        assertEquals(LinkRisk.NON_ASCII_HOST, verdict.risk)
        assertEquals("xn--80ak6aa92e.com", verdict.realAsciiHost)
    }

    @Test
    fun `safe display url rewrites unicode host to punycode`() {
        // "аpple.com" (Cyrillic 'а') must be surfaced as its ASCII/punycode form.
        val verdict = LinkSafetyInspector.inspect("https://\u0430pple.com/login?x=1")
        assertEquals("https://xn--pple-43d.com/login?x=1", verdict.safeDisplayUrl)
    }

    @Test
    fun `safe display url drops deceptive userinfo prefix`() {
        // The visible "apple.com" prefix is user-info; the real destination is evil.com.
        val verdict = LinkSafetyInspector.inspect("https://apple.com@evil.com/pay")
        assertEquals("https://evil.com/pay", verdict.safeDisplayUrl)
    }
}
