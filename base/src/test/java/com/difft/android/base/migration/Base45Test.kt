package com.difft.android.base.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 9285 vectors plus the strict-reject rules the three platforms agreed on. */
class Base45Test {

    @Test
    fun `RFC 9285 examples`() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertArrayEquals("ietf!".toByteArray(), Base45.decode("QED8WEX0"))
    }

    @Test
    fun `every byte length round-trips, including odd ones and a leading space`() {
        for (len in 1..70) {
            val bytes = ByteArray(len) { (it * 37 + len).toByte() }
            assertArrayEquals("len=$len", bytes, Base45.decode(Base45.encode(bytes)))
        }
        // 0x00 0x24 → n = 36 → first character is the space at index 36.
        val leadingSpace = Base45.encode(byteArrayOf(0x00, 0x24))
        assertTrue(leadingSpace.startsWith(" "))
        assertArrayEquals(byteArrayOf(0x00, 0x24), Base45.decode(leadingSpace))
    }

    @Test
    fun `rejects lengths leaving one character over`() {
        assertNull(Base45.decode("A"))
        assertNull(Base45.decode("BB8A"))
    }

    @Test
    fun `rejects a triple above 0xFFFF and a final pair above 0xFF`() {
        assertNull(Base45.decode(":::"))       // 44 + 44*45 + 44*2025 = 91124
        assertNull(Base45.decode("GGW"))       // 16 + 16*45 + 32*2025 = 65536
        assertNull(Base45.decode("BB8::"))     // final pair 44 + 44*45 = 2024
        assertNull(Base45.decode("BB8A6"))     // 10 + 6*45 = 280
        assertArrayEquals(byteArrayOf(0xFF.toByte()), Base45.decode("U5"))  // 30 + 5*45 = 255, the largest legal pair
    }

    @Test
    fun `rejects characters outside the alphabet and empty text`() {
        assertNull(Base45.decode("bb8"))
        assertNull(Base45.decode("BB{"))
        assertNull(Base45.decode(""))
        assertFalse(Base45.isAlphabet(""))
        assertFalse(Base45.isAlphabet("Aé"))
        assertTrue(Base45.isAlphabet("0Z $%*+-./:"))
    }
}
