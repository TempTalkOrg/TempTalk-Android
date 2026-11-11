package com.difft.android.base.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


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
        assertNull(Base45.decode(":::"))       
        assertNull(Base45.decode("GGW"))       
        assertNull(Base45.decode("BB8::"))     
        assertNull(Base45.decode("BB8A6"))     
        assertArrayEquals(byteArrayOf(0xFF.toByte()), Base45.decode("U5"))  
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
