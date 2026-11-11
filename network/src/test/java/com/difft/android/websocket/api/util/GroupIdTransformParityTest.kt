package com.difft.android.websocket.api.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test






class GroupIdTransformParityTest {

    
    private val desktopLocal = "WEEKF39A251FCA0865F7FF0CD534C13F3592"
    private val desktopBytes = byteArrayOf(
        0xF3.toByte(), 0x9A.toByte(), 0x25, 0x1F, 0xCA.toByte(), 0x08, 0x65, 0xF7.toByte(),
        0xFF.toByte(), 0x0C, 0xD5.toByte(), 0x34, 0xC1.toByte(), 0x3F, 0x35, 0x92.toByte()
    )

    
    @Test
    fun `PARITY-DESKTOP-VECTOR bidirectional`() {
        
        assertEquals(desktopLocal, desktopBytes.transformGroupIdFromServerToLocal())
        
        assertArrayEquals(desktopBytes, desktopLocal.transformGroupIdFromLocalToServer())
    }

    
    @Test
    fun `PARITY-DECODE-16 exact uppercase output`() {
        val bytes = ByteArray(16) { it.toByte() } 
        assertEquals(
            "WEEK000102030405060708090A0B0C0D0E0F",
            bytes.transformGroupIdFromServerToLocal()
        )
    }

    
    @Test
    fun `PARITY-ROUNDTRIP-WEEK identity both directions`() {
        
        assertEquals(
            desktopLocal,
            desktopLocal.transformGroupIdFromLocalToServer().transformGroupIdFromServerToLocal()
        )
        
        assertArrayEquals(
            desktopBytes,
            desktopBytes.transformGroupIdFromServerToLocal().transformGroupIdFromLocalToServer()
        )
    }

    
    
    
    @Test
    fun `PARITY-CASE uppercase emit and case-insensitive hex decode`() {
        val decoded = desktopBytes.transformGroupIdFromServerToLocal()
        
        val hexTail = decoded.removePrefix("WEEK")
        assertFalse(
            "f must emit UPPERCASE hex",
            hexTail.any { it in 'a'..'f' }
        )
        assertEquals(decoded, decoded.uppercase())

        
        
        val lower = "WEEK" + "f39a251fca0865f7ff0cd534c13f3592"
        val upper = "WEEK" + "F39A251FCA0865F7FF0CD534C13F3592"
        assertArrayEquals(
            lower.transformGroupIdFromLocalToServer(),
            upper.transformGroupIdFromLocalToServer()
        )
        assertArrayEquals(desktopBytes, lower.transformGroupIdFromLocalToServer())

        
        assertEquals(desktopLocal, lower.transformGroupIdFromLocalToServer().transformGroupIdFromServerToLocal())
    }

    
    
    @Test
    fun `PARITY-32-36-UNCHANGED plain string round trip no WEEK`() {
        val id32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"          
        val id36 = "12345678-1234-1234-1234-123456789012"      
        assertEquals(32, id32.toByteArray(Charsets.UTF_8).size)
        assertEquals(36, id36.toByteArray(Charsets.UTF_8).size)

        val local32 = id32.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal()
        val local36 = id36.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal()

        assertFalse("32-byte id must NOT gain a WEEK prefix", local32.startsWith("WEEK"))
        assertFalse("36-byte id must NOT gain a WEEK prefix (guards #1535)", local36.startsWith("WEEK"))
        assertEquals(id32, local32)
        assertEquals(id36, local36)

        
        assertArrayEquals(id32.toByteArray(Charsets.UTF_8), id32.transformGroupIdFromLocalToServer())
        assertArrayEquals(id36.toByteArray(Charsets.UTF_8), id36.transformGroupIdFromLocalToServer())
    }
}
