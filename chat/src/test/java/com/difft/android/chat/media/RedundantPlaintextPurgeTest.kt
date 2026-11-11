package com.difft.android.chat.media

import com.difft.android.chat.util.FileEncryptionUtil
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class RedundantPlaintextPurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = Random(3).nextBytes(64)
    private val root by lazy { tmp.newFolder("attachment") }

    private fun copy(dir: String, name: String, plaintext: Boolean, ciphertext: Boolean): File {
        val d = File(root, dir).apply { mkdirs() }
        val base = File(d, name)
        if (ciphertext) {
            val src = File(tmp.root, "src-$dir-$name").apply { writeBytes(Random(5).nextBytes(500)) }
            FileEncryptionUtil.encryptFile(src, File("${base.path}.encrypt"), key)
        }
        if (plaintext) base.writeBytes(Random(9).nextBytes(500))
        return base
    }

    @Test
    fun `plaintext beside a ciphertext with a usable key is removed`() {
        val leaked = copy("a", "voice.m4a", plaintext = true, ciphertext = true)

        val result = LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> key }

        assertFalse(leaked.exists())
        assertTrue(File("${leaked.path}.encrypt").exists(), "the ciphertext is kept")
        assertEquals(1, result.deleted)
        assertTrue(result.complete)
    }

    @Test
    fun `an uploading self-sent copy keeps its plaintext source`() {
        val staged = copy("b", "photo.jpg", plaintext = true, ciphertext = true)

        LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> ByteArray(0) }

        assertTrue(staged.exists())
    }

    @Test
    fun `plaintext without a ciphertext is left to the legacy migration`() {
        val legacy = copy("c", "doc.pdf", plaintext = true, ciphertext = false)

        LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> key }

        assertTrue(legacy.exists())
    }

    @Test
    fun `key lookup uses the copy directory and file name`() {
        val leaked = copy("localId42", "voice.m4a", plaintext = true, ciphertext = true)
        val asked = mutableListOf<Pair<String, String>>()

        LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { dir, name -> asked += dir to name; key }

        assertEquals(listOf("localId42" to "voice.m4a"), asked)
        assertFalse(leaked.exists())
    }

    @Test
    fun `a key that does not verify the ciphertext keeps the plaintext`() {
        
        val plaintext = copy("e", "voice.m4a", plaintext = true, ciphertext = true)
        val otherKey = Random(99).nextBytes(64)

        LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> otherKey }

        assertTrue(plaintext.exists())
    }

    @Test
    fun `a damaged ciphertext keeps the plaintext`() {
        val plaintext = copy("f", "voice.m4a", plaintext = true, ciphertext = true)
        java.io.RandomAccessFile(File("${plaintext.path}.encrypt"), "rw").use { raf ->
            raf.seek(20)
            val b = raf.read()
            raf.seek(20)
            raf.write(b xor 0x01)
        }

        LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> key }

        assertTrue(plaintext.exists())
    }

    @Test
    fun `only a complete pass over mounted external storage is recorded`() {
        val purge = LegacyPlaintextAttachmentMigration
        assertTrue(purge.mayStampRedundantPurge(complete = true, rootStorageState = android.os.Environment.MEDIA_MOUNTED))
        
        assertFalse(purge.mayStampRedundantPurge(complete = true, rootStorageState = android.os.Environment.MEDIA_UNKNOWN))
        assertFalse(purge.mayStampRedundantPurge(complete = false, rootStorageState = android.os.Environment.MEDIA_MOUNTED))
    }

    @Test
    fun `a missing root completes with nothing to do`() {
        val result = LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(File(tmp.root, "absent")) { _, _ -> key }

        assertTrue(result.complete)
        assertEquals(0, result.deleted)
    }

    @Test
    fun `a failing key lookup aborts the pass`() {
        copy("d", "voice.m4a", plaintext = true, ciphertext = true)

        assertFailsWith<IllegalStateException> {
            LegacyPlaintextAttachmentMigration.purgeRedundantPlaintext(root) { _, _ -> error("db unavailable") }
        }
    }
}
