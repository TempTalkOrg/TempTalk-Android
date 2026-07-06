package difft.android.messageserialization.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the single source of truth that decides whether an attachment is kept
 * **encrypted at rest** ([Attachment.keepEncryptedAtRest]) and the long-text classifier it now
 * relies on ([Attachment.isLongText]).
 *
 * These invoke the REAL extension functions (no re-implemented copy), so the download decision,
 * the sender-side keep/delete branch and the legacy-plaintext migration cannot silently drift.
 *
 * Focus of P4 (uniform encrypt-at-rest — ALL attachment types):
 *  - [Attachment.keepEncryptedAtRest] is now `true` for every type (images, video, voice, audio
 *    files, long text, generic documents / archives / octet-stream), aligning with Signal's model
 *    so no plaintext attachment ever touches disk;
 *  - [Attachment.isLongText] still uses an **exact** match — a generic `text/plain` must NOT be
 *    treated as long text (it has no long-text read path), guarding against an over-broad
 *    `contains`-style check. This classifier is orthogonal to keepEncryptedAtRest.
 */
class AttachmentEncryptedAtRestTest {

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
    fun `long text is kept encrypted at rest`() {
        assertTrue(attachment(CONTENT_TYPE_LONG_TEXT).keepEncryptedAtRest())
    }

    @Test
    fun `generic text-plain is not classified as long text (exact-match guard)`() {
        // Exact-match guard: a plain text file has no long-text read path, so it must NOT be
        // treated as long text — independent of the (now uniform) encrypt-at-rest decision.
        assertFalse(attachment("text/plain").isLongText())
    }

    @Test
    fun `image and video are kept encrypted at rest`() {
        assertTrue(attachment("image/jpeg").keepEncryptedAtRest())
        assertTrue(attachment("image/gif").keepEncryptedAtRest())
        assertTrue(attachment("video/mp4").keepEncryptedAtRest())
    }

    @Test
    fun `both voice messages and audio files are kept encrypted at rest`() {
        // P4: audio files (flags == 0) now ride the same encrypt-at-rest path as voice (flags == 1);
        // AudioMessageManager decrypts to memory bytes to play, so neither leaves plaintext on disk.
        assertTrue(attachment("audio/aac", flags = 1).keepEncryptedAtRest())
        assertTrue(attachment("audio/aac", flags = 0).keepEncryptedAtRest())
    }

    @Test
    fun `generic files are kept encrypted at rest (uniform P4 behaviour)`() {
        // P4: every remaining type is encrypted at rest and read via the decrypting content uri.
        assertTrue(attachment("application/pdf").keepEncryptedAtRest())
        assertTrue(attachment("application/zip").keepEncryptedAtRest())
        assertTrue(attachment("text/plain").keepEncryptedAtRest())
        assertTrue(attachment("application/octet-stream").keepEncryptedAtRest())
    }
}
