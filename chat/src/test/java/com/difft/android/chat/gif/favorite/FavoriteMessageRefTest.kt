package com.difft.android.chat.gif.favorite

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The source key a favorite-without-download carries.
 *
 * `attachmentId` here is favorites-internal bookkeeping — it is persisted with the pending row and
 * never sent; the CONFIRMED favorite's outbound id comes from isExist/uploadInfo under the account.
 * It therefore takes the message attachment's LOCAL id, which identifies exactly one copy, instead of
 * the server-side id every copy of a file shares (and which is empty on rows written since the id
 * stopped being seeded).
 */
class FavoriteMessageRefTest {

    private fun attachment(
        id: String = "",
        localId: String = "local-1",
        key: ByteArray? = byteArrayOf(1, 2, 3),
        fileName: String? = "cat.gif"
    ) = Attachment(
        id = id,
        authorityId = 42L,
        contentType = "image/gif",
        key = key,
        size = 10,
        thumbnail = null,
        digest = byteArrayOf(7),
        fileName = fileName,
        flags = 0,
        width = 2,
        height = 3,
        path = null,
        status = AttachmentStatus.SUCCESS.code,
        localId = localId
    )

    @Test
    fun `the source key is the attachment's local id`() {
        val ref = buildMessageRef(attachment(), "local-1")!!

        assertEquals("local-1", ref.attachmentId)
    }

    @Test
    fun `an empty server id no longer produces an empty source key`() {
        val ref = buildMessageRef(attachment(id = ""), "local-1")!!

        assertNotEquals("", ref.attachmentId)
    }

    @Test
    fun `two copies of the same file get distinct source keys`() {
        val first = buildMessageRef(attachment(localId = "local-a"), "local-a")!!
        val second = buildMessageRef(attachment(localId = "local-b"), "local-b")!!

        assertNotEquals(first.attachmentId, second.attachmentId)
    }

    @Test
    fun `the rest of the ref is unchanged`() {
        val ref = buildMessageRef(attachment(), "local-1")!!

        assertEquals("local-1", ref.messageId)
        assertEquals("cat.gif", ref.fileName)
        assertEquals(42L, ref.authorizeId)
        assertEquals("image/gif", ref.contentType)
    }

    @Test
    fun `an attachment without the material to fetch its bytes yields no ref`() {
        assertNull(buildMessageRef(attachment(key = null), "local-1"))
        assertNull(buildMessageRef(attachment(key = byteArrayOf()), "local-1"))
        assertNull(buildMessageRef(attachment(fileName = null), "local-1"))
    }
}
