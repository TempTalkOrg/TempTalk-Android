package com.difft.android.websocket.api.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Cross-platform gid-transform parity (design-report §14.8).
 *
 * Re-enabling the 16-byte -> "WEEK" + UPPERHEX decode branch in
 * [transformGroupIdFromServerToLocal] restores symmetric WEEK handling, matching:
 *   - iOS     TSGroupThread.transformToLocal/ServerGroupId (hexadecimalString.uppercaseString)
 *   - Desktop js/modules/id.js convertIdToV1/V2 (hexFromBinary(x).toUpperCase())
 *
 * These golden vectors ENCODE the three-platform contract:
 *   f = transformGroupIdFromServerToLocal   (decode, server bytes -> local string)
 *   g = transformGroupIdFromLocalToServer   (encode, local string -> server bytes)
 *
 * With the 16-byte decode branch live, f ∘ g and g ∘ f are identity for WEEK ids,
 * eliminating the §3.1 manufactured-envelope (C2/C3) round-trip false-drop residual
 * for legacy 16-byte groups. 32/36-byte plain-ASCII ids are unaffected (§14.8).
 */
class GroupIdTransformParityTest {

    // The Desktop-documented example: local string <-> its 16 raw bytes.
    private val desktopLocal = "WEEKF39A251FCA0865F7FF0CD534C13F3592"
    private val desktopBytes = byteArrayOf(
        0xF3.toByte(), 0x9A.toByte(), 0x25, 0x1F, 0xCA.toByte(), 0x08, 0x65, 0xF7.toByte(),
        0xFF.toByte(), 0x0C, 0xD5.toByte(), 0x34, 0xC1.toByte(), 0x3F, 0x35, 0x92.toByte()
    )

    // PARITY-DESKTOP-VECTOR — the Desktop-documented example, asserted BOTH directions.
    @Test
    fun `PARITY-DESKTOP-VECTOR bidirectional`() {
        // decode: 16 server bytes -> "WEEK" + UPPERHEX
        assertEquals(desktopLocal, desktopBytes.transformGroupIdFromServerToLocal())
        // encode: local string -> the same 16 raw bytes
        assertArrayEquals(desktopBytes, desktopLocal.transformGroupIdFromLocalToServer())
    }

    // PARITY-DECODE-16 — fixed 16-byte input decodes to exact uppercase "WEEK"+HEX.
    @Test
    fun `PARITY-DECODE-16 exact uppercase output`() {
        val bytes = ByteArray(16) { it.toByte() } // 0x00..0x0F
        assertEquals(
            "WEEK000102030405060708090A0B0C0D0E0F",
            bytes.transformGroupIdFromServerToLocal()
        )
    }

    // PARITY-ROUNDTRIP-WEEK — f ∘ g and g ∘ f are identity for a WEEK id and its 16 bytes.
    @Test
    fun `PARITY-ROUNDTRIP-WEEK identity both directions`() {
        // f(g(week)) == week
        assertEquals(
            desktopLocal,
            desktopLocal.transformGroupIdFromLocalToServer().transformGroupIdFromServerToLocal()
        )
        // g(f(bytes)) contentEquals bytes
        assertArrayEquals(
            desktopBytes,
            desktopBytes.transformGroupIdFromServerToLocal().transformGroupIdFromLocalToServer()
        )
    }

    // PARITY-CASE — f emits UPPERCASE hex; the hex decode in g is case-insensitive; round-trip
    // is case-stable. (The "WEEK" prefix detection in g is case-sensitive by design — only the
    // hex portion is case-insensitive, via Character.digit(_, 16).)
    @Test
    fun `PARITY-CASE uppercase emit and case-insensitive hex decode`() {
        val decoded = desktopBytes.transformGroupIdFromServerToLocal()
        // f emits uppercase: no lowercase a-f in the hex tail.
        val hexTail = decoded.removePrefix("WEEK")
        assertFalse(
            "f must emit UPPERCASE hex",
            hexTail.any { it in 'a'..'f' }
        )
        assertEquals(decoded, decoded.uppercase())

        // Hex decode is case-insensitive: WEEK + lowercase hex and WEEK + uppercase hex
        // encode to the same 16 bytes.
        val lower = "WEEK" + "f39a251fca0865f7ff0cd534c13f3592"
        val upper = "WEEK" + "F39A251FCA0865F7FF0CD534C13F3592"
        assertArrayEquals(
            lower.transformGroupIdFromLocalToServer(),
            upper.transformGroupIdFromLocalToServer()
        )
        assertArrayEquals(desktopBytes, lower.transformGroupIdFromLocalToServer())

        // Round-trip is case-stable: decoding either normalizes to the same UPPERCASE local id.
        assertEquals(desktopLocal, lower.transformGroupIdFromLocalToServer().transformGroupIdFromServerToLocal())
    }

    // PARITY-32-36-UNCHANGED — 32/36-byte UTF-8 ids round-trip as PLAIN String, no WEEK prefix.
    // Guards the #1535 regression class (36 must NOT be treated as WEEK).
    @Test
    fun `PARITY-32-36-UNCHANGED plain string round trip no WEEK`() {
        val id32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"          // 32 chars
        val id36 = "12345678-1234-1234-1234-123456789012"      // 36 chars
        assertEquals(32, id32.toByteArray(Charsets.UTF_8).size)
        assertEquals(36, id36.toByteArray(Charsets.UTF_8).size)

        val local32 = id32.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal()
        val local36 = id36.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal()

        assertFalse("32-byte id must NOT gain a WEEK prefix", local32.startsWith("WEEK"))
        assertFalse("36-byte id must NOT gain a WEEK prefix (guards #1535)", local36.startsWith("WEEK"))
        assertEquals(id32, local32)
        assertEquals(id36, local36)

        // Encode round-trip: plain ids are not gid-normalized, byte-for-byte identity.
        assertArrayEquals(id32.toByteArray(Charsets.UTF_8), id32.transformGroupIdFromLocalToServer())
        assertArrayEquals(id36.toByteArray(Charsets.UTF_8), id36.transformGroupIdFromLocalToServer())
    }
}
