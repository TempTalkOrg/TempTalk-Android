package com.difft.android.chat.attachment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether a forward may leave the device carrying a dead attachment pointer.
 *
 * This is the shape of issue #1181: forwarding asks the file-sharing service to re-authorize the file
 * the recipients are pointed at, and on a MISS — the server expired or reclaimed it — Android sent
 * the forward anyway, delivering an attachment nobody could ever download and telling the sender
 * nothing. iOS and Desktop both upload the local bytes instead.
 *
 * The decision is pinned here rather than through the send job because the job's arms are a real
 * upload and a real row write; what has to be right is which arm a leaf gets.
 */
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
        // The fast path — every ordinary forward. No upload, no row write, no new failure mode.
        assertEquals(ForwardLeafUpload.NotNeeded, decide(authorityId = 4242L, hasCiphertext = true))
    }

    @Test
    fun `an authorized leaf stays untouched even when nothing is on disk`() {
        // Local bytes are irrelevant while the server still holds the file: the recipient downloads.
        assertEquals(ForwardLeafUpload.NotNeeded, decide(authorityId = 4242L))
    }

    @Test
    fun `a miss with the stored ciphertext uploads that ciphertext`() {
        // The normal miss: encrypted at rest, so the plaintext is long gone. The stored ciphertext is
        // exactly the payload the recipient must receive — it already matches the recorded key.
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
        // Re-encrypting would produce an equivalent payload under a fresh IV for no gain; uploading
        // the stored ciphertext keeps the key AND spares a full re-encrypt pass.
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
        // Without a key there is nothing to upload under and nothing for a recipient to decrypt with;
        // such a leaf was never repairable, and must not become a failed send.
        assertEquals(ForwardLeafUpload.NotNeeded, decide(key = null, hasCiphertext = true))
        assertEquals(ForwardLeafUpload.NotNeeded, decide(key = ByteArray(0), hasCiphertext = true))
    }

    // The send-time source is the ORIGINAL's address, which a forward copy's own address may never
    // have received: the materialize-time copy can miss (a full disk) or run before the source has
    // finished downloading. Uploading from there instead of failing the send is the difference
    // between a repairable pointer and a permanently broken one.
    private val sourcePath = "/data/attachment/local-orig/photo.jpg"

    /** Every address the rule actually read, so a needless blocking probe is visible here. */
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
        // `toForwardCopy` refuses to capture either hint for a confidential source, so the second
        // chance is unreachable there — pinned here so a refactor cannot start persisting or
        // uploading confidential bytes through this path.
        assertEquals(ForwardLeafUpload.NoLocalBytes, decideWithSource(source = null, sourceHasCiphertext = true))
    }

    @Test
    fun `a send source that no longer holds bytes leaves the send failing`() {
        // Both hints are transient and captured earlier than this read; the source can be gone by now.
        assertEquals(ForwardLeafUpload.NoLocalBytes, decideWithSource())
    }

    @Test
    fun `the send source is never read while the leaf's own address holds bytes`() {
        // Resolving it is blocking IO on the send path — the ordinary repair must not pay for it.
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
