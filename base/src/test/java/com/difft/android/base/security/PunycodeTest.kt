package com.difft.android.base.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith





class PunycodeTest {

    @Test
    fun `decodes whole-script cyrillic apple lookalike`() {
        
        assertEquals("\u0430\u0440\u0440\u04cf\u0435", Punycode.decode("80ak6aa92e"))
    }

    @Test
    fun `decodes mixed latin plus cyrillic with basic segment`() {
        
        assertEquals("\u0430pple", Punycode.decode("pple-43d"))
    }

    @Test
    fun `decodes latin with diacritics`() {
        
        assertEquals("b\u00fccher", Punycode.decode("bcher-kva"))
    }

    @Test
    fun `malformed input throws`() {
        assertFailsWith<IllegalArgumentException> { Punycode.decode("!!!not-punycode!!!") }
    }
}
