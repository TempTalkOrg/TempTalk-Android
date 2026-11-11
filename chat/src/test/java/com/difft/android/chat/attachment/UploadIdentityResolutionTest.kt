package com.difft.android.chat.attachment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class UploadIdentityResolutionTest {

    private val localId = "8785ab93-local-copy"
    private val serverId = "3d963181-stored-copy"
    private val localDigest = byteArrayOf(0x21, 0x33, 0xBB.toByte(), 0xA4.toByte())
    private val serverCipherHash = "4F9A0284"
    private val serverDigest = byteArrayOf(0x4F, 0x9A.toByte(), 0x02, 0x84.toByte())

    @Test
    fun `server supplied both fields so its de-duplicated copy is adopted`() {
        val identity = resolveUploadIdentity(localId, localDigest, serverId, serverCipherHash)

        assertEquals(serverId, identity.attachmentId)
        assertArrayEquals(serverDigest, identity.digest)
        assertTrue(identity.adoptedFromServer)
    }

    @Test
    fun `a normal response carries neither field so the local pair stands`() {
        
        val identity = resolveUploadIdentity(localId, localDigest, null, null)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `an id without a cipherHash is not adopted alone`() {
        
        
        val identity = resolveUploadIdentity(localId, localDigest, serverId, null)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash without an id still corrects the digest`() {
        
        
        val identity = resolveUploadIdentity(localId, localDigest, null, serverCipherHash)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(serverDigest, identity.digest)
        assertTrue(identity.adoptedFromServer)
    }

    @Test
    fun `a blank cipherHash keeps the locally computed digest`() {
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "   ")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `an odd-length cipherHash keeps the locally computed digest`() {
        
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "4F9A028")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a non-hex cipherHash of even length keeps the locally computed digest`() {
        
        
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "T5oChA==")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash of a different length keeps the locally computed digest`() {
        
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "4F9A")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash echoing the local values is not reported as a change`() {
        
        val identity = resolveUploadIdentity(localId, serverDigest, localId, serverCipherHash)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(serverDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }
}
