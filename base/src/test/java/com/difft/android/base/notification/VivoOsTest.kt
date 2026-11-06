package com.difft.android.base.notification

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VivoOsTest {

    @Test
    fun `OriginOS display ids parse to their major version`() {
        assertEquals(6, VivoOs.parseOriginMajor("OriginOS 6"))
        assertEquals(5, VivoOs.parseOriginMajor("OriginOS 5.1"))
        assertEquals(6, VivoOs.parseOriginMajor(" originos 6.0 "))
        assertEquals(12, VivoOs.parseOriginMajor("OriginOS 12"))
    }

    @Test
    fun `Funtouch and unknown text do not parse`() {
        assertNull(VivoOs.parseOriginMajor("Funtouch OS 14"))
        assertNull(VivoOs.parseOriginMajor(""))
        assertNull(VivoOs.parseOriginMajor("OriginOS"))
        assertNull(VivoOs.parseOriginMajor("OriginOS Ocean"))
    }

    @Test
    fun `the direct page is supported from OriginOS 6`() {
        assertTrue(VivoOs.supportsDirectPermissionPage(6))
        assertTrue(VivoOs.supportsDirectPermissionPage(7))
        assertFalse(VivoOs.supportsDirectPermissionPage(5))
        assertFalse(VivoOs.supportsDirectPermissionPage(null))
    }
}
