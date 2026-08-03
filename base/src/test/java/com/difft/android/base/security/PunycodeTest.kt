package com.difft.android.base.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 3492 decode vectors for [Punycode]. Pure JVM — no Android runtime needed.
 * Inputs are the label with the `xn--` prefix already stripped.
 */
class PunycodeTest {

    @Test
    fun `decodes whole-script cyrillic apple lookalike`() {
        // xn--80ak6aa92e -> аррӏе
        assertEquals("\u0430\u0440\u0440\u04cf\u0435", Punycode.decode("80ak6aa92e"))
    }

    @Test
    fun `decodes mixed latin plus cyrillic with basic segment`() {
        // xn--pple-43d -> аpple (Cyrillic 'а' + Latin "pple")
        assertEquals("\u0430pple", Punycode.decode("pple-43d"))
    }

    @Test
    fun `decodes latin with diacritics`() {
        // xn--bcher-kva -> bücher
        assertEquals("b\u00fccher", Punycode.decode("bcher-kva"))
    }

    @Test
    fun `malformed input throws`() {
        assertFailsWith<IllegalArgumentException> { Punycode.decode("!!!not-punycode!!!") }
    }
}
