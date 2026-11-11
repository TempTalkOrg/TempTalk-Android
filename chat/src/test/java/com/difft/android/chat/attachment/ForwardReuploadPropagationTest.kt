package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.message.createForward
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test










class ForwardReuploadPropagationTest {

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

    private fun attachment(authorityId: Long, localId: String = "local-src") = Attachment(
        id = "server-id",
        authorityId = authorityId,
        contentType = "image/jpeg",
        key = byteArrayOf(1, 2, 3),
        size = 10,
        thumbnail = null,
        digest = byteArrayOf(9, 9),
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.SUCCESS.code,
        localId = localId
    )

    private fun forward(attachments: List<Attachment>?, nested: List<Forward>? = null) =
        Forward(1L, 0, false, "author", "text", attachments, nested, null)

    @Test
    fun `the miss signal survives the per-target copy at every nesting level`() {
        
        val source = ForwardContext(
            listOf(
                forward(
                    listOf(attachment(authorityId = 0L, localId = "a"), attachment(authorityId = 777L, localId = "b")),
                    nested = listOf(forward(listOf(attachment(authorityId = 0L, localId = "c"))))
                )
            ),
            false
        )

        val copy = source.deepCopyWithNewAttachmentIdentities()

        fun walk(f: Forward): List<Attachment> = f.attachments.orEmpty() + f.forwards.orEmpty().flatMap { walk(it) }
        val authorityIds = copy.forwards.orEmpty().flatMap { walk(it) }.map { it.authorityId }
        assertEquals(listOf(0L, 777L, 0L), authorityIds)
    }

    @Test
    fun `the wire pointer is built from the leaf the repair mutates`() {
        val leaf = attachment(authorityId = 0L)
        val tree = forward(listOf(leaf))

        
        
        leaf.authorityId = 5150L
        leaf.digest = byteArrayOf(4, 2)

        val pointer = createForward(tree).getAttachments(0)

        assertEquals(5150L, pointer.id)
        assertArrayEquals(byteArrayOf(4, 2), pointer.digest.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), pointer.key.toByteArray())
    }
}
