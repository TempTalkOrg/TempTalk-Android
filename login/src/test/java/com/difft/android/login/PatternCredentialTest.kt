package com.difft.android.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternCredentialTest {

    @Test
    fun `created credential is marked and verifies its own sequence only`() {
        val credential = PatternCredential.create(listOf(0, 1, 2, 5))

        val parsed = PatternCredential.parse(credential.format())!!
        assertTrue(parsed.createdWithNewRule)
        assertTrue(PasscodeUtil.verifyPassword(parsed.hash, parsed.salt, "0,1,2,5"))
        assertFalse(PasscodeUtil.verifyPassword(parsed.hash, parsed.salt, "0,2,5"))
    }

    @Test
    fun `stored strings without the marker parse as legacy and readers taking two parts still work`() {
        val parsed = PatternCredential.parse("abc:def")!!
        assertFalse(parsed.createdWithNewRule)

        val marked = PatternCredential("abc", "def", createdWithNewRule = true).format()
        assertEquals("abc", marked.split(":")[0])
        assertEquals("def", marked.split(":")[1])
        assertNull(PatternCredential.parse("abc"))
    }
}
