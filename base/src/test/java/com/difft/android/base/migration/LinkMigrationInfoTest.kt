package com.difft.android.base.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The link QR's `dataMigrationInfo` parameter: base64url without padding of a v2 payload. This
 * module only checks the wrapping and the magic byte and hands the payload on as bare-code text;
 * anything else degrades silently to "no migration info" so the link login itself never fails.
 */
class LinkMigrationInfoTest {
    /** Vector B of the pairing proposal (§2.6): the Desktop login-link inner payload. */
    private val vectorHex =
        "0202010a0000059f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f1234565a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a0e4e6174652773204d6163426f6f6b"
    private val vectorBytes = ByteArray(vectorHex.length / 2) { vectorHex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val vectorEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(vectorBytes)

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `decodes the vector to the bare-code text of the same bytes`() {
        assertEquals(89, vectorBytes.size)
        val text = LinkMigrationInfo.decode(vectorEncoded)
        assertEquals(Base45.encode(vectorBytes), text)
        assertArrayEquals(vectorBytes, Base45.decode(text!!))
    }

    @Test
    fun `padding and surrounding whitespace are tolerated`() {
        assertEquals(LinkMigrationInfo.decode(vectorEncoded), LinkMigrationInfo.decode(" $vectorEncoded= \n"))
    }

    @Test
    fun `missing or blank parameter means no migration info`() {
        assertNull(LinkMigrationInfo.decode(null))
        assertNull(LinkMigrationInfo.decode(""))
        assertNull(LinkMigrationInfo.decode("   "))
    }

    @Test
    fun `garbage, the retired JSON shape and foreign magic are rejected`() {
        assertNull(LinkMigrationInfo.decode("not base64url!!"))
        assertNull(LinkMigrationInfo.decode(encode("{\"v\":1}".toByteArray())))
        assertNull(LinkMigrationInfo.decode(encode(byteArrayOf(0x01) + vectorBytes.drop(1).toByteArray())))
        assertNull(LinkMigrationInfo.decode(encode(ByteArray(0))))
        assertNull(LinkMigrationInfo.decode(encode(ByteArray(MigrationQrWire.MAX_PAYLOAD_BYTES + 1) { 0x02 })))
    }
}
