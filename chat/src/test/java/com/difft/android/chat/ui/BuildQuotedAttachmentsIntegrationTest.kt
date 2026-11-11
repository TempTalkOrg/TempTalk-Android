package com.difft.android.chat.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.chat.message.TextChatMessage
import com.google.gson.Gson
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Draft
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config





@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BuildQuotedAttachmentsIntegrationTest {

    private fun messageWith(attachment: Attachment?, author: String = "user-123"): TextChatMessage =
        TextChatMessage().apply {
            id = "msg-test-001"
            authorId = author
            isMine = false
            timeStamp = 1705320000000L
            this.attachment = attachment
        }

    private fun imageAttachment() = Attachment(
        id = "att-img-001", authorityId = 100L, contentType = "image/jpeg",
        key = byteArrayOf(1, 2, 3), size = 12345, thumbnail = null, digest = byteArrayOf(4, 5, 6),
        fileName = "photo.jpg", flags = 0, width = 100, height = 100,
        path = "/data/photo.jpg", status = AttachmentStatus.SUCCESS.code,
    )

    

    @Test
    fun `buildQuotedAttachments emits type-entry with null thumbnail for image message`() {
        val message = messageWith(imageAttachment())

        val result = ChatMessageInputFragment().buildQuotedAttachments(message)

        assertNotNull("Expected non-null result for image message", result)
        assertEquals(1, result!!.size)
        val qa = result[0]
        assertEquals("image/jpeg", qa.contentType)
        assertEquals("photo.jpg", qa.fileName)
        assertEquals(0, qa.flags)
        assertNull("No thumbnail bytes are generated or carried anymore", qa.thumbnail)
    }

    

    @Test
    fun `buildQuotedAttachments emits type-entry for audio message`() {
        val attachment = Attachment(
            id = "att-audio-001", authorityId = 200L, contentType = "audio/aac",
            key = null, size = 5000, thumbnail = null, digest = null, fileName = null,
            flags = 1, width = 0, height = 0, path = null, status = AttachmentStatus.SUCCESS.code,
        )
        val message = messageWith(attachment)

        val result = ChatMessageInputFragment().buildQuotedAttachments(message)

        assertNotNull(result)
        assertEquals(1, result!!.size)
        val qa = result[0]
        assertEquals("audio/aac", qa.contentType)
        assertEquals(1, qa.flags)
        assertNull(qa.thumbnail)
    }

    

    @Test
    fun `buildQuotedAttachments returns null for text-only message`() {
        val message = messageWith(attachment = null, author = "user-456").apply {
            message = "Hello world"
        }

        val result = ChatMessageInputFragment().buildQuotedAttachments(message)

        assertNull("Expected null result for text-only message", result)
    }

    

    @Test
    fun `draft serialize-deserialize preserves quote type-entry`() {
        val quote = Quote(
            id = 1705320000000L, author = "user-123", text = "[Image]",
            attachments = listOf(QuotedAttachment("image/jpeg", "photo.jpg", thumbnail = null, flags = 0)),
        )
        val draft = Draft(content = "reply text", quote = quote)

        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(draft), Draft::class.java)

        val restoredQa = restored.quote?.attachments?.firstOrNull()
        assertNotNull("Expected restored quoted attachment", restoredQa)
        assertEquals("image/jpeg", restoredQa!!.contentType)
        assertEquals("photo.jpg", restoredQa.fileName)
        assertEquals(0, restoredQa.flags)
        assertNull("Type-entry carries no thumbnail bytes", restoredQa.thumbnail)
    }
}
