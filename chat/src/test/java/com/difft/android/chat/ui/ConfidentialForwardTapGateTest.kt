package com.difft.android.chat.ui

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which CONFIDENTIAL forward bubbles are judged by the attachment tap policy instead of always
 * opening the detail page: only a single forward whose sole content is an attachment. Anything still
 * viewable — text alongside the attachment, nested forwards, a combined forward — keeps opening.
 * The policy itself is pinned in AttachmentDownloadDecisionTest.
 */
class ConfidentialForwardTapGateTest {

    private fun attachment(status: Int) = Attachment(
        id = "server-id",
        authorityId = 1L,
        contentType = "image/jpeg",
        key = ByteArray(64),
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = "a.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = "",
        status = status
    )

    private fun forward(
        text: String? = null,
        attachments: List<Attachment>? = null,
        forwards: List<Forward>? = null
    ) = Forward(
        id = 1L,
        type = 0,
        isFromGroup = false,
        author = "+1",
        text = text,
        attachments = attachments,
        forwards = forwards,
        mentions = null
    )

    @Test
    fun `a lone attachment is the forward's only content, whatever its state`() {
        for (status in AttachmentStatus.entries) {
            val forwards = listOf(forward(attachments = listOf(attachment(status.code))))
            assertEquals("status=$status", status.code, attachmentOnlyForward(forwards)?.status)
        }
    }

    @Test
    fun `text next to the attachment keeps the forward openable`() {
        val forwards = listOf(forward(text = "see this", attachments = listOf(attachment(AttachmentStatus.EXPIRED.code))))
        assertNull(attachmentOnlyForward(forwards))
    }

    @Test
    fun `nested or combined forwards are never attachment-only`() {
        val nested = listOf(
            forward(
                attachments = listOf(attachment(AttachmentStatus.EXPIRED.code)),
                forwards = listOf(forward(text = "inner"))
            )
        )
        assertNull(attachmentOnlyForward(nested))

        val combined = listOf(
            forward(attachments = listOf(attachment(AttachmentStatus.EXPIRED.code))),
            forward(text = "second")
        )
        assertNull(attachmentOnlyForward(combined))

        assertNull(attachmentOnlyForward(listOf(forward(text = "just text"))))
    }
}
