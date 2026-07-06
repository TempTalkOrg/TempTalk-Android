package difft.android.messageserialization.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Attachment.isAnimatedImage] — the single predicate that classifies an attachment
 * as an animated image (gif or animated webp) for the "[Gif]" label. Invokes the REAL extension so
 * the label sites and the sender-side flag cannot silently drift.
 *
 * Rules under test:
 *  - authoritative [FLAG_GIF] bit set by the sender → animated (regardless of MIME);
 *  - un-flagged `image/gif` → animated (legacy / cross-client MIME fallback);
 *  - un-flagged `image/webp` → static (cannot be classified from MIME alone);
 *  - a normal static image → static;
 *  - the GIF bit read is bitwise, so it must NOT collide with the voice `flags == 1` check.
 */
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
        // Sender-marked webp (GIPHY) — authoritative, no MIME dependency.
        assertTrue(attachment("image/webp", flags = FLAG_GIF).isAnimatedImage())
    }

    @Test
    fun `un-flagged image-gif is animated via MIME fallback`() {
        assertTrue(attachment("image/gif").isAnimatedImage())
        // Trimmed MIME is still matched.
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
        // Voice uses flags == 1; the GIF bit (4) must not be triggered by it.
        assertFalse(attachment("audio/aac", flags = 1).isAnimatedImage())
    }

    @Test
    fun `combined gif and other bits still detected`() {
        // Bitwise read: the GIF bit is detected even when other bits coexist.
        assertTrue(attachment("image/webp", flags = FLAG_GIF or 1).isAnimatedImage())
    }
}
