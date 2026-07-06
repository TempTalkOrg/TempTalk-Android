package com.difft.android.base.glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Unit tests for [EncryptedCacheCoder] — the AES/CTR coder behind Glide's encrypted RESOURCE cache.
 *
 * Pure JVM (javax.crypto), no Android dependencies, so this runs as a plain JUnit test. Covers:
 * round-trip correctness across sizes, ciphertext confidentiality + per-file randomness, and the
 * three documented failure modes (wrong key, foreign file, truncation) that must surface as
 * [IOException] so callers treat them as cache misses.
 */
class EncryptedCacheCoderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterKey = ByteArray(32) { it.toByte() }
    private val coder = EncryptedCacheCoder(masterKey)

    private fun newFile(name: String = "cache.bin"): File = tempFolder.newFile(name)

    private fun encrypt(coder: EncryptedCacheCoder, file: File, payload: ByteArray) {
        coder.encryptedOutput(file).use { it.write(payload) }
    }

    private fun decryptAll(coder: EncryptedCacheCoder, file: File): ByteArray =
        coder.encryptedInput(file).use { it.readBytes() }

    @Test
    fun `round trips an empty payload`() {
        val file = newFile()
        encrypt(coder, file, ByteArray(0))
        assertArrayEquals(ByteArray(0), decryptAll(coder, file))
    }

    @Test
    fun `round trips a small payload`() {
        val file = newFile()
        val payload = "hello-encrypted-cache".toByteArray()
        encrypt(coder, file, payload)
        assertArrayEquals(payload, decryptAll(coder, file))
    }

    @Test
    fun `round trips a large multi block payload`() {
        val file = newFile()
        val payload = ByteArray(512 * 1024).also { SecureRandom().nextBytes(it) }
        encrypt(coder, file, payload)
        assertArrayEquals(payload, decryptAll(coder, file))
    }

    @Test
    fun `decrypts correctly when read in small chunks`() {
        val file = newFile()
        val payload = ByteArray(8192).also { SecureRandom().nextBytes(it) }
        encrypt(coder, file, payload)

        val out = java.io.ByteArrayOutputStream()
        coder.encryptedInput(file).use { input ->
            val buf = ByteArray(7) // deliberately awkward size to exercise partial reads
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
            }
        }
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `payload is not stored in plaintext on disk`() {
        val file = newFile()
        val payload = "TOP-SECRET-MARKER-0123456789".toByteArray()
        encrypt(coder, file, payload)

        val raw = file.readBytes()
        // The marker must not appear anywhere in the on-disk bytes.
        assertFalse(
            "plaintext payload leaked into cache file",
            indexOf(raw, payload) >= 0
        )
    }

    @Test
    fun `same payload yields different ciphertext on each write`() {
        val payload = "deterministic-input".toByteArray()
        val fileA = newFile("a.bin")
        val fileB = newFile("b.bin")
        encrypt(coder, fileA, payload)
        encrypt(coder, fileB, payload)

        val bytesA = fileA.readBytes()
        val bytesB = fileB.readBytes()
        // Random per-file salt ⇒ different derived key ⇒ different ciphertext, even for equal input.
        assertNotEquals(
            "cache files for identical payload should differ (per-file randomness)",
            bytesA.toList(), bytesB.toList()
        )
        // But both still decrypt back to the same payload.
        assertArrayEquals(payload, decryptAll(coder, fileA))
        assertArrayEquals(payload, decryptAll(coder, fileB))
    }

    @Test(expected = IOException::class)
    fun `decrypting with a different master key throws`() {
        val file = newFile()
        encrypt(coder, file, "payload".toByteArray())

        val otherKey = ByteArray(32) { (it + 1).toByte() }
        EncryptedCacheCoder(otherKey).encryptedInput(file).use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun `decrypting a non cache file throws`() {
        val file = newFile()
        file.writeBytes(ByteArray(128).also { SecureRandom().nextBytes(it) }) // no MAGIC header
        coder.encryptedInput(file).use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun `decrypting an empty file throws`() {
        val file = newFile()
        // zero-length file → premature EOF while reading the MAGIC header
        coder.encryptedInput(file).use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun `decrypting a header truncated file throws`() {
        val file = newFile()
        encrypt(coder, file, "payload".toByteArray())
        // Keep only the first few bytes of MAGIC → premature EOF during header read.
        val truncated = file.readBytes().copyOfRange(0, 4)
        file.writeBytes(truncated)
        coder.encryptedInput(file).use { it.readBytes() }
    }

    @Test
    fun `handles consumers can detect a foreign file without crashing`() {
        // Mirrors how EncryptedCacheResourceDecoder.handles() probes ownership: a foreign file
        // must produce IOException (caught upstream), not silently decode to garbage.
        val file = newFile()
        file.writeBytes("just a normal jpeg-ish blob".toByteArray())
        val isOurs = try {
            coder.encryptedInput(file).use { true }
        } catch (e: IOException) {
            false
        }
        assertFalse(isOurs)
    }

    @Test
    fun `handles consumers accept our own file`() {
        val file = newFile()
        encrypt(coder, file, "payload".toByteArray())
        val isOurs = try {
            coder.encryptedInput(file).use { true }
        } catch (e: IOException) {
            false
        }
        assertTrue(isOurs)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
