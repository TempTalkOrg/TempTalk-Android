package com.difft.android.chat.ui

import com.difft.android.chat.fileshare.FileExistResp
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writing one isExist fast-pass response back into the forward tree.
 *
 * The response belongs to exactly ONE leaf. Matching by the server-side attachment id could not
 * express that: every copy of a file carries the same id, so `find` stopped at the first leaf and any
 * later sibling kept a zero authority id and the pre-authorization digest — a silently broken send
 * whenever the same file was forwarded twice in one action. Local ids are unique per leaf.
 */
class ChangeAttachmentDigestTest {

    private fun attachment(localId: String, id: String = "server-id") = Attachment(
        id = id,
        authorityId = 0L,
        contentType = "image/jpeg",
        key = byteArrayOf(1, 2, 3),
        size = 10,
        thumbnail = null,
        digest = byteArrayOf(0),
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.LOADING.code,
        localId = localId,
        isForwardCopy = true
    )

    private fun forward(attachments: List<Attachment>?, nested: List<Forward>? = null) =
        Forward(1L, 0, false, "author", "text", attachments, nested, null)

    private fun response(authorizeId: Long = 555L, cipherHash: String = "0a0b") = FileExistResp(
        attachmentId = "server-id",
        authorizeId = authorizeId,
        cipherHash = cipherHash,
        cipherHashType = "sha256",
        exists = true,
        url = ""
    )

    @Test
    fun `the response lands on the leaf it was requested for`() {
        val target = attachment("local-a")
        val other = attachment("local-b")

        changeAttachmentDigest(forward(listOf(target, other)), "local-a", response(), "hash-a")

        assertEquals(555L, target.authorityId)
        assertEquals("hash-a", target.fileHash)
        assertArrayEquals(byteArrayOf(0x0a, 0x0b), target.digest)
    }

    @Test
    fun `a sibling copy of the same file is left untouched`() {
        val target = attachment("local-a")
        val sibling = attachment("local-b")

        changeAttachmentDigest(forward(listOf(target, sibling)), "local-a", response(), "hash-a")

        assertEquals(0L, sibling.authorityId)
        assertEquals(null, sibling.fileHash)
    }

    @Test
    fun `duplicate siblings each receive their own response`() {
        // The same file forwarded twice in one action: two leaves, same server id, two isExist calls.
        val first = attachment("local-a")
        val second = attachment("local-b")
        val tree = forward(listOf(first, second))

        changeAttachmentDigest(tree, "local-a", response(authorizeId = 111L), "hash-a")
        changeAttachmentDigest(tree, "local-b", response(authorizeId = 222L), "hash-b")

        assertEquals(111L, first.authorityId)
        assertEquals(222L, second.authorityId)
        assertEquals("hash-a", first.fileHash)
        assertEquals("hash-b", second.fileHash)
    }

    @Test
    fun `a leaf nested any number of forwards deep is still reached`() {
        val deep = attachment("local-deep")
        val tree = forward(
            listOf(attachment("local-top")),
            nested = listOf(forward(null, nested = listOf(forward(listOf(deep)))))
        )

        changeAttachmentDigest(tree, "local-deep", response(authorizeId = 999L), "hash-deep")

        assertEquals(999L, deep.authorityId)
    }

    @Test
    fun `a local id that names no leaf changes nothing`() {
        val only = attachment("local-a")

        changeAttachmentDigest(forward(listOf(only)), "local-missing", response(), "hash")

        assertEquals(0L, only.authorityId)
        assertEquals(null, only.fileHash)
    }
}
