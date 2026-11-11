package com.difft.android.chat.attachment

import org.junit.Assert.assertEquals
import org.junit.Test












class ForwardReuploadDecisionTest {

    private val key = ByteArray(64) { 7 }
    private val path = "/data/attachment/local-copy/photo.jpg"

    private fun decide(
        authorityId: Long = 0L,
        key: ByteArray? = this.key,
        fileName: String? = "photo.jpg",
        hasPlaintext: Boolean = false,
        hasCiphertext: Boolean = false
    ) = forwardLeafUpload(authorityId, key, fileName, path, hasPlaintext, hasCiphertext)

    @Test
    fun `an authorized leaf is left completely alone`() {
        
        assertEquals(ForwardLeafUpload.NotNeeded, decide(authorityId = 4242L, hasCiphertext = true))
    }

    @Test
    fun `an authorized leaf stays untouched even when nothing is on disk`() {
        
        assertEquals(ForwardLeafUpload.NotNeeded, decide(authorityId = 4242L))
    }

    @Test
    fun `a miss with the stored ciphertext uploads that ciphertext`() {
        
        
        assertEquals(
            ForwardLeafUpload.FromStoredCiphertext(path),
            decide(hasCiphertext = true)
        )
    }

    @Test
    fun `a miss with only a legacy plaintext encrypts and uploads it`() {
        assertEquals(ForwardLeafUpload.FromPlaintext(path), decide(hasPlaintext = true))
    }

    @Test
    fun `ciphertext wins over a plaintext left behind, so the recorded key stays valid`() {
        
        
        assertEquals(
            ForwardLeafUpload.FromStoredCiphertext(path),
            decide(hasPlaintext = true, hasCiphertext = true)
        )
    }

    @Test
    fun `a miss with no local bytes fails the send instead of delivering a dead pointer`() {
        assertEquals(ForwardLeafUpload.NoLocalBytes, decide())
    }

    @Test
    fun `a miss with no file name cannot be located on disk, so it fails too`() {
        assertEquals(ForwardLeafUpload.NoLocalBytes, decide(fileName = null, hasCiphertext = true))
        assertEquals(ForwardLeafUpload.NoLocalBytes, decide(fileName = "", hasCiphertext = true))
    }

    @Test
    fun `a leaf carrying no key keeps today's behaviour rather than failing a send`() {
        
        
        assertEquals(ForwardLeafUpload.NotNeeded, decide(key = null, hasCiphertext = true))
        assertEquals(ForwardLeafUpload.NotNeeded, decide(key = ByteArray(0), hasCiphertext = true))
    }

    
    
    
    
    private val sourcePath = "/data/attachment/local-orig/photo.jpg"

    
    private val probed = mutableListOf<String>()

    private fun decideWithSource(
        authorityId: Long = 0L,
        key: ByteArray? = this.key,
        fileName: String? = "photo.jpg",
        hasPlaintext: Boolean = false,
        hasCiphertext: Boolean = false,
        source: String? = sourcePath,
        sourceHasPlaintext: Boolean = false,
        sourceHasCiphertext: Boolean = false
    ) = forwardLeafUpload(
        authorityId = authorityId,
        key = key,
        fileName = fileName,
        basePath = path,
        bytesAt = { candidate ->
            probed += candidate
            if (candidate == path) LeafBytes(hasPlaintext, hasCiphertext)
            else LeafBytes(sourceHasPlaintext, sourceHasCiphertext)
        },
        sendSourceBasePath = { source }
    )

    @Test
    fun `an empty leaf address uploads the ciphertext still sitting at the send source`() {
        assertEquals(
            ForwardLeafUpload.FromStoredCiphertext(sourcePath),
            decideWithSource(sourceHasCiphertext = true)
        )
    }

    @Test
    fun `an empty leaf address encrypts and uploads a plaintext send source`() {
        assertEquals(
            ForwardLeafUpload.FromPlaintext(sourcePath),
            decideWithSource(sourceHasPlaintext = true)
        )
    }

    @Test
    fun `a confidential source carries no hint, so the send still fails`() {
        
        
        
        assertEquals(ForwardLeafUpload.NoLocalBytes, decideWithSource(source = null, sourceHasCiphertext = true))
    }

    @Test
    fun `a send source that no longer holds bytes leaves the send failing`() {
        
        assertEquals(ForwardLeafUpload.NoLocalBytes, decideWithSource())
    }

    @Test
    fun `the send source is never read while the leaf's own address holds bytes`() {
        
        assertEquals(ForwardLeafUpload.FromStoredCiphertext(path), decideWithSource(hasCiphertext = true))
        assertEquals(listOf(path), probed)
    }

    @Test
    fun `an authorized leaf never reaches the send source`() {
        assertEquals(ForwardLeafUpload.NotNeeded, decideWithSource(authorityId = 4242L))
        assertEquals(listOf(path), probed)
    }

    @Test
    fun `a leaf with no file name skips the send source, which could not locate it either`() {
        assertEquals(
            ForwardLeafUpload.NoLocalBytes,
            decideWithSource(fileName = null, sourceHasCiphertext = true)
        )
        assertEquals(listOf(path), probed)
    }
}
