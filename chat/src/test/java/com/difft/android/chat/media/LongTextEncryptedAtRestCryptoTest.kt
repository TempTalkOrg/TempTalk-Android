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







class LongTextEncryptedAtRestCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    
    private val key = ByteArray(64) { it.toByte() }

    
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
        
        val len = EncryptedAttachmentAccess.encryptedFile(basePath).length()
        val cipherLen = len - 16 - 32
        assertTrue(cipherLen > 0 && cipherLen % 16L == 0L)
    }

    @Test
    fun `hasEncrypted rejects a truncated ciphertext`() {
        val basePath = encryptText("this download will be truncated")
        val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        
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
        
        
        val basePath = encryptText("forwarded long text, status never flips to SUCCESS")
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))
        assertTrue(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 1))
    }

    @Test
    fun `isLongTextReady size-verifies a legacy plaintext-only file`() {
        
        
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
        encFile.writeBytes(bytes.copyOf(bytes.size - 1)) 

        
        assertFalse(EncryptedAttachmentAccess.isLongTextReady(basePath, expectedPlainSize = 0))

        
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

        
        val bytes = encFile.readBytes()
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        encFile.writeBytes(bytes)
        assertFalse(FileDecryptionUtil.verifyMac(encFile, key))
    }
}
