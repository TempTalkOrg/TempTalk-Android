package difft.android.messageserialization.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test









class AttachmentLongTextTest {

    private fun attachment(contentType: String, flags: Int = 0): Attachment = Attachment(
        id = "1",
        authorityId = 0L,
        contentType = contentType,
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "File-2026-07-02.txt",
        flags = flags,
        width = 0,
        height = 0,
        path = null,
        status = 0,
    )

    @Test
    fun `long text content type is classified as long text`() {
        assertTrue(attachment(CONTENT_TYPE_LONG_TEXT).isLongText())
    }

    @Test
    fun `generic text-plain is not classified as long text (exact-match guard)`() {
        
        
        assertFalse(attachment("text/plain").isLongText())
    }
}
