package com.difft.android.base.utils

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral guardrails for [Base64] (#1093).
 *
 * Written test-first against the vendored iHarder impl to pin the production contract, then held
 * unchanged across the swap to the `java.util.Base64` shim — every case must pass against BOTH.
 * This is also the framework-assumption test for the shim's reliance on `java.util.Base64`
 * (T4/T6/T7 exercise the real JDK class, not a mock).
 *
 * Covers all 6 live members exercised by the 21 consumers: encodeBytes(ByteArray),
 * encodeBytes(ByteArray, Int) via NO_OPTIONS, encodeBytesWithoutPadding, decode, decodeWithoutPadding.
 */
class Base64Test {

    // 32 bytes — identity-key-shaped material (JsonUtil / identity-key without-padding path).
    private val identityKeyBytes: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

    // 52 bytes — signaling-key-shaped material (PipeDecryptTool standard path).
    private val signalingKeyBytes: ByteArray = ByteArray(52) { (it * 5 + 1).toByte() }

    /** T1 — without-padding round-trip (identity-key sized). */
    @Test
    fun withoutPadding_roundTrips_andHasNoPadding() {
        val encoded = Base64.encodeBytesWithoutPadding(identityKeyBytes)
        assertFalse(encoded.endsWith("="), "encodeBytesWithoutPadding must not emit trailing '='")
        val decoded = Base64.decodeWithoutPadding(encoded)
        assertContentEquals(identityKeyBytes, decoded)
    }

    /** T2 — standard round-trip (signaling-key sized) + NO_OPTIONS overload parity. */
    @Test
    fun standard_roundTrips_andNoOptionsOverloadMatches() {
        val encoded = Base64.encodeBytes(signalingKeyBytes)
        val decoded = Base64.decode(encoded)
        assertContentEquals(signalingKeyBytes, decoded)

        // encodeBytes(source, NO_OPTIONS) must equal encodeBytes(source).
        assertEquals(encoded, Base64.encodeBytes(signalingKeyBytes, Base64.NO_OPTIONS))
        assertEquals(0, Base64.NO_OPTIONS)
    }

    /** T3 — empty input edge. */
    @Test
    fun empty_encodesToEmpty_andDecodesToEmpty() {
        assertEquals("", Base64.encodeBytes(ByteArray(0)))
        assertContentEquals(ByteArray(0), Base64.decode(""))
    }

    /** T4 — whitespace / line-break tolerance on decode. */
    @Test
    fun decode_toleratesEmbeddedWhitespace() {
        val clean = Base64.encodeBytes(signalingKeyBytes)
        // Wrap into 20-char lines with spaces + newlines interspersed.
        val wrapped = clean.chunked(20).joinToString("\n ")
        val fromWrapped = Base64.decode(wrapped)
        val fromClean = Base64.decode(clean)
        assertContentEquals(fromClean, fromWrapped)
        assertContentEquals(signalingKeyBytes, fromWrapped)
    }

    /** T5 — malformed input surfaces IOException (NOT IllegalArgumentException). SECURITY-adjacent. */
    @Test
    fun decode_malformed_throwsIOException() {
        assertFailsWith<IOException> { Base64.decode("!!!not base64!!!") }
    }

    /**
     * T5b — non-alphabet characters must throw even when their count leaves a decodable remnant
     * (Cursor Bugbot, PR #1096): a MIME decoder would silently skip them and "successfully"
     * decode corrupt input; strict decoding must reject it like the original implementation did.
     */
    @Test
    fun decode_invalidCharsWithDecodableLength_throwsIOException() {
        assertFailsWith<IOException> { Base64.decode("!!!!") }
        assertFailsWith<IOException> { Base64.decode("QUJD!QUJD") }
    }

    /** T6 — decodeWithoutPadding re-pads both len%4==2 and len%4==3 cases. */
    @Test
    fun decodeWithoutPadding_repadsBothRemainderBranches() {
        // 31 bytes -> 42 no-pad chars (42 % 4 == 2); 32 bytes -> 43 no-pad chars (43 % 4 == 3).
        val bytes31 = ByteArray(31) { (it + 1).toByte() }
        val enc31 = Base64.encodeBytesWithoutPadding(bytes31)
        assertEquals(2, enc31.length % 4)
        assertContentEquals(bytes31, Base64.decodeWithoutPadding(enc31))

        val enc32 = Base64.encodeBytesWithoutPadding(identityKeyBytes)
        assertEquals(3, enc32.length % 4)
        assertContentEquals(identityKeyBytes, Base64.decodeWithoutPadding(enc32))
    }

    /** T7 — standard RFC-4648 alphabet (+ and /), never URL-safe (- or _). */
    @Test
    fun encode_usesStandardAlphabet_notUrlSafe() {
        // 0xFB 0xFF -> "+/8=" : first sextet 62 ('+'), second 63 ('/').
        val encoded = Base64.encodeBytes(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
        assertTrue(encoded.contains('+'), "expected standard '+' in $encoded")
        assertTrue(encoded.contains('/'), "expected standard '/' in $encoded")
        assertFalse(encoded.contains('-'), "URL-safe '-' must not appear in $encoded")
        assertFalse(encoded.contains('_'), "URL-safe '_' must not appear in $encoded")
    }
}
