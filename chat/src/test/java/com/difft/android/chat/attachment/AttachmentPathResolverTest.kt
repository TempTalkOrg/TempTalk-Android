package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.Attachment
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [AttachmentPathResolver] addressing contract.
 *
 * `FileUtil.getMessageAttachmentFilePath` is stubbed to a deterministic shape so the assertions pin
 * WHICH key ends up in the directory segment (the resolver's only decision) rather than the app's
 * real storage root.
 */
class AttachmentPathResolverTest {

    @Before
    fun setUp() {
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers { "/root/attachment/${firstArg<String>()}/" }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun attachment(
        fileName: String? = "photo.jpg",
        authorityId: Long = 42L,
        localId: String = "local-1",
        isForwardCopy: Boolean = false
    ) = Attachment(
        id = "att-id",
        authorityId = authorityId,
        contentType = "image/jpeg",
        key = null,
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = fileName,
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = 3,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    @Test
    fun `a normal attachment is addressed under its own localId`() {
        val att = attachment()

        assertEquals("/root/attachment/local-1/", AttachmentPathResolver.directoryFor(att))
        assertEquals("/root/attachment/local-1/photo.jpg", AttachmentPathResolver.fileFor(att))
    }

    @Test
    fun `forward copy is addressed under its own localId, not the authorityId`() {
        val att = attachment(authorityId = 987L, localId = "local-fwd", isForwardCopy = true)

        assertEquals("/root/attachment/local-fwd/", AttachmentPathResolver.directoryFor(att))
        assertEquals("/root/attachment/local-fwd/photo.jpg", AttachmentPathResolver.fileFor(att))
    }

    @Test
    fun `forwarded and normal attachments follow the same single rule`() {
        val normal = attachment(localId = "local-n", isForwardCopy = false)
        val forwarded = attachment(localId = "local-f", isForwardCopy = true)

        assertEquals("/root/attachment/local-n/photo.jpg", AttachmentPathResolver.fileFor(normal))
        assertEquals("/root/attachment/local-f/photo.jpg", AttachmentPathResolver.fileFor(forwarded))
    }

    @Test
    fun `two forward copies of the same authorized file get separate directories`() {
        val first = attachment(authorityId = 987L, localId = "local-a", isForwardCopy = true)
        val second = attachment(authorityId = 987L, localId = "local-b", isForwardCopy = true)

        assertEquals("/root/attachment/local-a/photo.jpg", AttachmentPathResolver.fileFor(first))
        assertEquals("/root/attachment/local-b/photo.jpg", AttachmentPathResolver.fileFor(second))
    }

    @Test
    fun `nesting depth does not change addressing`() {
        // A leaf three forwards deep is addressed exactly like a top-level one: by its own localId.
        val deepLeaf = attachment(localId = "local-deep", isForwardCopy = true)

        assertEquals("/root/attachment/local-deep/photo.jpg", AttachmentPathResolver.fileFor(deepLeaf))
    }

    @Test
    fun `missing file name yields the bare directory`() {
        assertEquals("/root/attachment/local-1/", AttachmentPathResolver.fileFor(attachment(fileName = null)))
        assertEquals(
            "/root/attachment/local-fwd/",
            AttachmentPathResolver.fileFor(attachment(fileName = null, localId = "local-fwd", isForwardCopy = true))
        )
    }

    @Test
    fun `row directory key mirrors the domain rule`() {
        assertEquals("local-9", AttachmentPathResolver.directoryKeyForRow("local-9"))
    }

    @Test
    fun `row that cannot name a directory key yields null so the caller keeps the legacy reading`() {
        assertNull(AttachmentPathResolver.directoryKeyForRow(null))
        assertNull(AttachmentPathResolver.directoryKeyForRow(""))
    }

    @Test
    fun `a staged file lands exactly where the reader will look for it`() {
        // The writer/reader agreement the five hand-rolled write sites used to break: staging under
        // the OWNER MESSAGE id while every read gate resolved the attachment's own directory.
        val localId = "local-new"
        val staged = AttachmentPathResolver.stagingFileFor(localId, "photo.jpg")

        val row = attachment(localId = localId)
        assertEquals(staged, AttachmentPathResolver.fileFor(row))
        assertEquals("/root/attachment/local-new/photo.jpg", staged)
    }

    @Test
    fun `a staged forward copy lands where its reader looks too`() {
        val localId = "local-new-fwd"
        val staged = AttachmentPathResolver.stagingFileFor(localId, "photo.jpg")

        val row = attachment(localId = localId, isForwardCopy = true)
        assertEquals(staged, AttachmentPathResolver.fileFor(row))
    }

    @Test
    fun `the owner message id reaches the migrator but never the returned path`() {
        val seen = mutableListOf<Triple<String, String, String?>>()
        AttachmentPathResolver.migrator = AttachmentPathResolver.Migrator { att, dir, legacyOwner ->
            seen += Triple(att.localId, dir, legacyOwner)
            false
        }
        try {
            val att = attachment(localId = "local-m")

            assertEquals(
                "/root/attachment/local-m/photo.jpg",
                AttachmentPathResolver.materializedFileFor(att, "msg-1")
            )
            assertEquals(listOf(Triple("local-m", "/root/attachment/local-m/", "msg-1")), seen)
        } finally {
            AttachmentPathResolver.migrator = null
        }
    }
}
