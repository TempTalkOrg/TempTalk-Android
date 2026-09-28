package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Forward copying must mint a NEW local identity per attachment leaf, per target message, while
 * leaving server identity untouched — that pairing is what decouples copies on disk without changing
 * anything on the wire.
 */
class ForwardAttachmentCopyTest {

    @Before
    fun setUp() {
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers { "/root/attachment/${firstArg<String>()}/" }
        mockkObject(EncryptedAttachmentAccess)
        every { EncryptedAttachmentAccess.isReadable(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun attachment(
        id: String = "server-id",
        localId: String = "local-src",
        isForwardCopy: Boolean = false
    ) = Attachment(
        id = id,
        authorityId = 777L,
        contentType = "image/jpeg",
        key = byteArrayOf(1, 2, 3),
        size = 10,
        thumbnail = null,
        digest = byteArrayOf(9, 9),
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = "/legacy/photo.jpg",
        status = AttachmentStatus.SUCCESS.code,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    private fun forward(attachments: List<Attachment>?, nested: List<Forward>? = null) =
        Forward(1L, 0, false, "author", "text", attachments, nested, null)

    private fun allAttachments(context: ForwardContext): List<Attachment> {
        fun walk(f: Forward): List<Attachment> =
            f.attachments.orEmpty() + f.forwards.orEmpty().flatMap { walk(it) }
        return context.forwards.orEmpty().flatMap { walk(it) }
    }

    @Test
    fun `deep copy mints a fresh localId for every leaf at every nesting level`() {
        val source = ForwardContext(
            listOf(
                forward(
                    listOf(attachment(localId = "a"), attachment(localId = "b")),
                    nested = listOf(forward(listOf(attachment(localId = "c"))))
                )
            ),
            false
        )

        val copy = source.deepCopyWithNewAttachmentIdentities()

        val copiedIds = allAttachments(copy).map { it.localId }
        assertEquals(3, copiedIds.size)
        assertEquals(3, copiedIds.toSet().size)
        assertTrue(copiedIds.none { it in setOf("a", "b", "c") })
        // The source tree is untouched.
        assertEquals(listOf("a", "b", "c"), allAttachments(source).map { it.localId })
    }

    @Test
    fun `each target gets its own identities from the same source context`() {
        val source = ForwardContext(listOf(forward(listOf(attachment(localId = "a")))), false)

        val first = source.deepCopyWithNewAttachmentIdentities()
        val second = source.deepCopyWithNewAttachmentIdentities()

        assertNotEquals(
            allAttachments(first).single().localId,
            allAttachments(second).single().localId
        )
    }

    @Test
    fun `server identity is carried over verbatim`() {
        val original = attachment(id = "server-id")

        val copy = original.toForwardCopy()

        assertEquals(original.id, copy.id)
        assertEquals(original.authorityId, copy.authorityId)
        assertEquals(original.key, copy.key)
        assertEquals(original.digest, copy.digest)
        assertEquals(original.fileName, copy.fileName)
    }

    @Test
    fun `a copy is marked as a forward copy and starts LOADING`() {
        val copy = attachment().toForwardCopy()

        assertTrue(copy.isForwardCopy)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
    }

    @Test
    fun `plain to forward captures the source attachment's own directory`() {
        val copy = attachment(localId = "local-orig").toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))

        assertEquals("/root/attachment/local-orig/photo.jpg", copy.forwardSourceFilePath)
    }

    @Test
    fun `re-forward captures the source copy's own directory, not the carrier message`() {
        val alreadyForwarded = attachment(localId = "local-src", isForwardCopy = true)

        val copy = alreadyForwarded.toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))

        assertEquals("/root/attachment/local-src/photo.jpg", copy.forwardSourceFilePath)
        assertNotEquals("local-src", copy.localId)
    }

    @Test
    fun `confidential source is never given a copy source`() {
        val copy = attachment().toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = true))

        assertNull(copy.forwardSourceFilePath)
    }

    @Test
    fun `unreadable source is not carried, but the send-time rescue hint is`() {
        every { EncryptedAttachmentAccess.isReadable(any()) } returns false

        val copy = attachment(localId = "local-orig")
            .toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))

        // Nothing at the current address is not yet a verdict: the file may still be at a
        // pre-per-copy address, which only the migration can reach and only off the main thread. The
        // ORIGINAL travels, not the copy — the copy's identity was minted after the file was
        // written, so the original is the only object that can be resolved to that address.
        assertNull(copy.forwardSourceFilePath)
        val fallback = copy.forwardSourceFallback
        assertNotNull(fallback)
        assertEquals("local-orig", fallback?.original?.localId)
        assertEquals("msg-1", fallback?.legacyOwnerMessageId)
    }

    @Test
    fun `a readable source carries no rescue hint — there is nothing left to look for`() {
        val copy = attachment().toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))

        assertNull(copy.forwardSourceFallback)
    }

    @Test
    fun `a confidential source carries no rescue hint either`() {
        every { EncryptedAttachmentAccess.isReadable(any()) } returns false

        val copy = attachment().toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = true))

        // The suppression must hold on BOTH paths, or the fallback would quietly hand a confidential
        // attachment the persistent copy it must never gain.
        assertNull(copy.forwardSourceFilePath)
        assertNull(copy.forwardSourceFallback)
    }

    @Test
    fun `per-target re-mint keeps the rescue hint captured when the forward was built`() {
        every { EncryptedAttachmentAccess.isReadable(any()) } returns false
        val built = ForwardContext(
            listOf(
                forward(
                    listOf(
                        attachment(localId = "local-orig")
                            .toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))
                    )
                )
            ),
            false
        )

        val perTarget = built.deepCopyWithNewAttachmentIdentities()

        val fallback = allAttachments(perTarget).single().forwardSourceFallback
        assertEquals("local-orig", fallback?.original?.localId)
        assertEquals("msg-1", fallback?.legacyOwnerMessageId)
    }

    @Test
    fun `per-target re-mint keeps the source captured when the forward was built`() {
        val built = ForwardContext(
            listOf(forward(listOf(attachment(localId = "local-orig").toForwardCopy(ForwardSourceContext(ownerMessageId = "msg-1", isConfidential = false))))),
            false
        )

        val perTarget = built.deepCopyWithNewAttachmentIdentities()

        val copy = allAttachments(perTarget).single()
        assertEquals("/root/attachment/local-orig/photo.jpg", copy.forwardSourceFilePath)
        assertNotEquals(allAttachments(built).single().localId, copy.localId)
    }
}
