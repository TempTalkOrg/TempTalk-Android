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
 *  - the [EncryptedAttachmentAccess.isLongTextReady] "fully downloaded" gate: the file decides — a
 *    valid ciphertext is ready whatever the row's status/progress or the message's direction say
 *    (this is what unblocks FORWARDED long text whose serialized attachment status never flips to
 *    SUCCESS), while a legacy plaintext-only file must match the recorded plaintext size;
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
    fun `isLongTextReady treats a valid ciphertext as ready whatever size the row records`() {
        // Core of the forwarded-long-text fix: a forwarded attachment's serialized status stays
        // LOADING and its in-memory progress is transient, and a forward copy's message id says
        // nothing about who wrote the bytes — none of that is consulted. The complete, MAC-guarded
        // ".encrypt" on disk counts as fully downloaded; the ciphertext arm ignores the plaintext size.
        val basePath = encryptText("forwarded long text, status never flips to SUCCESS")
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 1))
    }

    @Test
    fun `isLongTextReady size-verifies a legacy plaintext-only file`() {
        // Plaintext can't self-verify truncation, so it must match the row's recorded plaintext size;
        // an unknown size (<= 0) accepts any non-empty plaintext.
        val text = "legacy plaintext long text (pre-migration)"
        val basePath = writePlaintext(text)
        val size = text.toByteArray(Charsets.UTF_8).size
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = size))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = size + 1))
    }

    @Test
    fun `isLongTextReady is not ready when neither ciphertext nor plaintext exists`() {
        val basePath = File(tempFolder.root, "missing-longtext.txt").absolutePath
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 42))
    }

    @Test
    fun `isLongTextReady rejects a truncated ciphertext then size-verifies the plaintext`() {
        val basePath = encryptText("this download is truncated", name = "truncated-longtext.txt")
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        val bytes = encFile.readBytes()
        encFile.writeBytes(bytes.copyOf(bytes.size - 1)) // break the (len-48)%16 invariant

        // Truncated ciphertext alone (no plaintext) is never ready.
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))

        // Once a legacy plaintext exists at the same base path, readiness is its size check.
        val fallback = "legacy plaintext fallback"
        File(basePath).writeText(fallback, Charsets.UTF_8)
        val size = fallback.toByteArray(Charsets.UTF_8).size
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = size - 1))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = size))
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
