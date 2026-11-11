package com.difft.android.base.utils

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue












class Base64Test {

    
    private val identityKeyBytes: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

    
    private val signalingKeyBytes: ByteArray = ByteArray(52) { (it * 5 + 1).toByte() }

    
    @Test
    fun withoutPadding_roundTrips_andHasNoPadding() {
        val encoded = Base64.encodeBytesWithoutPadding(identityKeyBytes)
        assertFalse(encoded.endsWith("="), "encodeBytesWithoutPadding must not emit trailing '='")
        val decoded = Base64.decodeWithoutPadding(encoded)
        assertContentEquals(identityKeyBytes, decoded)
    }

    
    @Test
    fun standard_roundTrips_andNoOptionsOverloadMatches() {
        val encoded = Base64.encodeBytes(signalingKeyBytes)
        val decoded = Base64.decode(encoded)
        assertContentEquals(signalingKeyBytes, decoded)

        
        assertEquals(encoded, Base64.encodeBytes(signalingKeyBytes, Base64.NO_OPTIONS))
        assertEquals(0, Base64.NO_OPTIONS)
    }

    
    @Test
    fun empty_encodesToEmpty_andDecodesToEmpty() {
        assertEquals("", Base64.encodeBytes(ByteArray(0)))
        assertContentEquals(ByteArray(0), Base64.decode(""))
    }

    
    @Test
    fun decode_toleratesEmbeddedWhitespace() {
        val clean = Base64.encodeBytes(signalingKeyBytes)
        
        val wrapped = clean.chunked(20).joinToString("\n ")
        val fromWrapped = Base64.decode(wrapped)
        val fromClean = Base64.decode(clean)
        assertContentEquals(fromClean, fromWrapped)
        assertContentEquals(signalingKeyBytes, fromWrapped)
    }

    
    @Test
    fun decode_malformed_throwsIOException() {
        assertFailsWith<IOException> { Base64.decode("!!!not base64!!!") }
    }

    




    @Test
    fun decode_invalidCharsWithDecodableLength_throwsIOException() {
        assertFailsWith<IOException> { Base64.decode("!!!!") }
        assertFailsWith<IOException> { Base64.decode("QUJD!QUJD") }
    }

    
    @Test
    fun decodeWithoutPadding_repadsBothRemainderBranches() {
        
        val bytes31 = ByteArray(31) { (it + 1).toByte() }
        val enc31 = Base64.encodeBytesWithoutPadding(bytes31)
        assertEquals(2, enc31.length % 4)
        assertContentEquals(bytes31, Base64.decodeWithoutPadding(enc31))

        val enc32 = Base64.encodeBytesWithoutPadding(identityKeyBytes)
        assertEquals(3, enc32.length % 4)
        assertContentEquals(identityKeyBytes, Base64.decodeWithoutPadding(enc32))
    }

    
    @Test
    fun encode_usesStandardAlphabet_notUrlSafe() {
        
        val encoded = Base64.encodeBytes(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
        assertTrue(encoded.contains('+'), "expected standard '+' in $encoded")
        assertTrue(encoded.contains('/'), "expected standard '/' in $encoded")
        assertFalse(encoded.contains('-'), "URL-safe '-' must not appear in $encoded")
        assertFalse(encoded.contains('_'), "URL-safe '_' must not appear in $encoded")
    }
}
