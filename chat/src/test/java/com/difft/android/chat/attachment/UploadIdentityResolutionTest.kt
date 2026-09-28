package com.difft.android.chat.attachment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which digest an upload publishes when the server answers `uploadInfo` with a de-duplicated copy.
 *
 * This is the shape of issue #1184: two flows encrypting the same plaintext concurrently produce the
 * same content-derived key but different ciphertexts (random IV), the server keeps one of them, and
 * the loser used to ship the digest of the copy the server discarded. Android's verifyMac never
 * looks at the digest, so only Mac/iOS saw the attachment fail to load.
 *
 * Pinned here rather than through the helper because the helper's arms are a real OSS upload and a
 * real uploadInfo round trip; what has to be right is which pair the pointer carries.
 */
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
        // gson leaves both null on the ordinary (non-dedup) response, which returns authorizeId only.
        val identity = resolveUploadIdentity(localId, localDigest, null, null)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `an id without a cipherHash is not adopted alone`() {
        // The id is inert for sending (the wire pointer is authorityId), so on its own there is
        // nothing to adopt: only the digest decides which ciphertext the pointer describes.
        val identity = resolveUploadIdentity(localId, localDigest, serverId, null)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash without an id still corrects the digest`() {
        // The digest is the load-bearing half: keeping the local one because no attachmentId came
        // with it would publish the digest of the ciphertext the server discarded.
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
        // decodeDigestHex rejects an odd character count; a garbage digest is worse than the local one.
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "4F9A028")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a non-hex cipherHash of even length keeps the locally computed digest`() {
        // decodeDigestHex rejects only an ODD character count: a non-hex character decodes to 0xFF,
        // so an even-length base64 value would otherwise be adopted as a garbage digest.
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "T5oChA==")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash of a different length keeps the locally computed digest`() {
        // Both sides hash the same payload with the same algorithm; a shorter value is not that hash.
        val identity = resolveUploadIdentity(localId, localDigest, serverId, "4F9A")

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(localDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }

    @Test
    fun `a cipherHash echoing the local values is not reported as a change`() {
        // The server confirming our own upload: same pair, so nothing was de-duplicated to log.
        val identity = resolveUploadIdentity(localId, serverDigest, localId, serverCipherHash)

        assertEquals(localId, identity.attachmentId)
        assertArrayEquals(serverDigest, identity.digest)
        assertFalse(identity.adoptedFromServer)
    }
}
