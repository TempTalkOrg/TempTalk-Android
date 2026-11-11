package difft.android.messageserialization.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test













class AttachmentAnimatedImageTest {

    private fun attachment(contentType: String, flags: Int = 0): Attachment = Attachment(
        id = "1",
        authorityId = 0L,
        contentType = contentType,
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "file",
        flags = flags,
        width = 0,
        height = 0,
        path = null,
        status = 0,
    )

    @Test
    fun `gif flag set is animated`() {
        
        assertTrue(attachment("image/webp", flags = FLAG_GIF).isAnimatedImage())
    }

    @Test
    fun `un-flagged image-gif is animated via MIME fallback`() {
        assertTrue(attachment("image/gif").isAnimatedImage())
        
        assertTrue(attachment(" image/gif ").isAnimatedImage())
    }

    @Test
    fun `un-flagged image-webp stays static`() {
        assertFalse(attachment("image/webp").isAnimatedImage())
    }

    @Test
    fun `normal static image is not animated`() {
        assertFalse(attachment("image/jpeg").isAnimatedImage())
        assertFalse(attachment("image/png").isAnimatedImage())
    }

    @Test
    fun `voice flag does not read as gif`() {
        
        assertFalse(attachment("audio/aac", flags = 1).isAnimatedImage())
    }

    @Test
    fun `combined gif and other bits still detected`() {
        
        assertTrue(attachment("image/webp", flags = FLAG_GIF or 1).isAnimatedImage())
    }
}
