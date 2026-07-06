package com.difft.android.chat.media

import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.chat.util.FileEncryptionUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the crypto layer that backs long-text "encrypted at rest" (P3).
 *
 * Long text is stored as `<basePath>.encrypt` in the standard at-rest layout
 * (`[IV16][AES-CBC/PKCS5 ciphertext][HMAC32]`) and read back on demand as an in-memory String via
 * a decrypting stream — the exact mechanism [EncryptedAttachmentAccess.readDecryptedText] uses on
 * its ciphertext branch (the provider hop is an Android-integration concern out of scope here).
 *
 * Pure JVM (javax.crypto + java.io), no Android dependencies, so this runs as a plain JUnit test.
 * Covers:
 *  - UTF-8 round-trip through encrypt → stream-decrypt (incl. multibyte and a large ~1MB payload),
 *    proving plaintext survives losslessly and never touches disk on read;
 *  - the [EncryptedAttachmentAccess.hasEncrypted] readiness gate that the long-text bubble/attach
 *    view now use in place of a plaintext `isFileValid` check — accepts a structurally-valid
 *    ciphertext and rejects a truncated one (self-healing re-download);
 *  - the [EncryptedAttachmentAccess.isLongTextReady] "fully downloaded" gate: a valid ciphertext is
 *    ready regardless of the status/progress signal (this is what unblocks FORWARDED long text whose
 *    serialized attachment status never flips to SUCCESS), while a legacy plaintext-only file still
 *    defers to that signal;
 *  - HMAC integrity: a valid file verifies, a tampered byte fails.
 */
class LongTextEncryptedAtRestCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // 64-byte key: key[0:32] = AES-256, key[32:64] = HMAC-SHA256 (matches PushTextSendJob layout).
    private val key = ByteArray(64) { it.toByte() }

    /** Encrypt [text] into `<basePath>.encrypt` and return the base path. */
    private fun encryptText(text: String, name: String = "File-2026-07-02.txt"): String {
        val basePath = File(tempFolder.root, name).absolutePath
        val plainFile = tempFolder.newFile("$name.plain").apply { writeText(text, Charsets.UTF_8) }
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        FileEncryptionUtil.encryptFile(plainFile, encFile, key)
        return basePath
    }

    private fun decryptText(basePath: String): String =
        FileDecryptionUtil.decryptToStream(EncryptedAttachmentAccess.encryptedFile(basePath), key)
            .reader(Charsets.UTF_8)
            .use { it.readText() }

    /** Write a legacy plaintext file AT [basePath] (no `.encrypt`) and return that base path. */
    private fun writePlaintext(text: String, name: String = "plain-only.txt"): String {
        val basePath = File(tempFolder.root, name).absolutePath
        File(basePath).writeText(text, Charsets.UTF_8)
        return basePath
    }

    @Test
    fun `small ascii text round trips through encrypt and stream decrypt`() {
        val text = "hello encrypted-at-rest long text"
        val basePath = encryptText(text)
        assertEquals(text, decryptText(basePath))
    }

    @Test
    fun `empty-ish and multibyte utf8 text round trips`() {
        val text = "多字节测试 🚀 mixed ASCII / 日本語 / emoji 😀\nsecond line\ttab"
        val basePath = encryptText(text)
        assertEquals(text, decryptText(basePath))
    }

    @Test
    fun `large ~1MB text round trips`() {
        // Repeated multibyte block so byte length comfortably exceeds the 4KB oversize threshold.
        val text = buildString {
            val chunk = "长文本附件明文不落盘-line-with-emoji-😀-0123456789\n"
            while (length < 1_000_000) append(chunk)
        }
        val basePath = encryptText(text)
        assertEquals(text, decryptText(basePath))
    }

    @Test
    fun `hasEncrypted accepts a structurally valid long-text ciphertext`() {
        val basePath = encryptText("readiness gate should treat this as ready")
        assertTrue(EncryptedAttachmentAccess.hasEncrypted(basePath))
        // Structural invariant: len = IV(16) + 16*n + MAC(32) ⇒ (len - 48) is a positive multiple of 16.
        val len = EncryptedAttachmentAccess.encryptedFile(basePath).length()
        val cipherLen = len - 16 - 32
        assertTrue(cipherLen > 0 && cipherLen % 16L == 0L)
    }

    @Test
    fun `hasEncrypted rejects a truncated ciphertext`() {
        val basePath = encryptText("this download will be truncated")
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        // Drop the final byte to simulate an early-EOF download: breaks the (len-48)%16 invariant.
        val bytes = encFile.readBytes()
        encFile.writeBytes(bytes.copyOf(bytes.size - 1))
        assertFalse(EncryptedAttachmentAccess.hasEncrypted(basePath))
    }

    @Test
    fun `hasEncrypted is false when no ciphertext exists`() {
        val basePath = File(tempFolder.root, "missing.txt").absolutePath
        assertFalse(EncryptedAttachmentAccess.hasEncrypted(basePath))
    }

    @Test
    fun `isLongTextReady treats a valid ciphertext as ready regardless of the status signal`() {
        // Core of the forwarded-long-text fix: a forwarded attachment's serialized status stays
        // LOADING and its in-memory progress is transient, so plaintextStatusReady is false — but the
        // complete, MAC-guarded ".encrypt" on disk must still count as fully downloaded.
        val basePath = encryptText("forwarded long text, status never flips to SUCCESS")
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = false))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = true))
    }

    @Test
    fun `isLongTextReady defers to the status signal for a legacy plaintext-only file`() {
        // Plaintext can't self-verify truncation, so it still needs the caller's
        // status==SUCCESS / progress==100 / own-device-send signal.
        val basePath = writePlaintext("legacy plaintext long text (pre-migration)")
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = false))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = true))
    }

    @Test
    fun `isLongTextReady is not ready when neither ciphertext nor plaintext exists`() {
        val basePath = File(tempFolder.root, "missing-longtext.txt").absolutePath
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = true))
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = false))
    }

    @Test
    fun `isLongTextReady rejects a truncated ciphertext then applies the plaintext gate`() {
        val basePath = encryptText("this download is truncated", name = "truncated-longtext.txt")
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        val bytes = encFile.readBytes()
        encFile.writeBytes(bytes.copyOf(bytes.size - 1)) // break the (len-48)%16 invariant

        // Truncated ciphertext alone (no plaintext) is never ready, even with a "ready" status.
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = true))

        // Once a legacy plaintext exists at the same base path, readiness falls back to the status gate.
        File(basePath).writeText("legacy plaintext fallback", Charsets.UTF_8)
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = false))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, plaintextStatusReady = true))
    }

    @Test
    fun `verifyMac passes for an intact file and fails after tampering`() {
        val basePath = encryptText("integrity protected long text")
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        assertTrue(FileDecryptionUtil.verifyMac(encFile, key))

        // Flip a bit inside the ciphertext region (after the 16-byte IV) without changing the length.
        val bytes = encFile.readBytes()
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        encFile.writeBytes(bytes)
        assertFalse(FileDecryptionUtil.verifyMac(encFile, key))
    }
}
