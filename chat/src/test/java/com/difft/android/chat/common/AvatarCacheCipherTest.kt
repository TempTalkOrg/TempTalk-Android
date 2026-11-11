package com.difft.android.chat.common

import com.difft.android.base.glide.EncryptedCacheCoder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit











class AvatarCacheCipherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterKey = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        AvatarCacheCipher.masterKeyProvider = { masterKey }
    }

    @After
    fun tearDown() {
        
        AvatarCacheCipher.masterKeyProvider = { null }
    }

    private fun cacheFile(name: String = "avatar_abc123"): File = File(tempFolder.root, name)

    private fun readAll(file: File): ByteArray =
        AvatarCacheCipher.openDecrypting(file).use { it.readBytes() }

    @Test
    fun `round trips a small payload`() {
        val file = cacheFile()
        val payload = "a-tiny-avatar-png".toByteArray()
        AvatarCacheCipher.writeEncrypted(file, payload)
        assertArrayEquals(payload, readAll(file))
    }

    @Test
    fun `round trips a large payload`() {
        val file = cacheFile()
        val payload = ByteArray(256 * 1024).also { SecureRandom().nextBytes(it) }
        AvatarCacheCipher.writeEncrypted(file, payload)
        assertArrayEquals(payload, readAll(file))
    }

    @Test
    fun `write produces ciphertext on disk and no leftover temp`() {
        val file = cacheFile()
        val payload = "TOP-SECRET-AVATAR-MARKER-0123456789".toByteArray()
        AvatarCacheCipher.writeEncrypted(file, payload)

        assertTrue("cache file must exist after write", file.exists())
        assertTrue("cache file must be our ciphertext (MAGIC)", EncryptedCacheCoder.hasMagic(file))

        val raw = file.readBytes()
        assertFalse("plaintext payload leaked into cache file", indexOf(raw, payload) >= 0)

        val leftovers = tempFolder.root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("no temp file should remain after atomic write: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `plaintextLength of ciphertext is fileLen minus header overhead`() {
        val file = cacheFile()
        val payload = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        AvatarCacheCipher.writeEncrypted(file, payload)

        assertEquals(payload.size.toLong(), AvatarCacheCipher.plaintextLength(file))
        
        assertEquals(
            payload.size.toLong() + EncryptedCacheCoder.HEADER_OVERHEAD,
            file.length()
        )
    }

    @Test
    fun `openDecrypting passes through legacy plaintext without MAGIC`() {
        val file = cacheFile()
        val payload = "legacy-plaintext-avatar".toByteArray()
        file.writeBytes(payload) 

        assertFalse(EncryptedCacheCoder.hasMagic(file))
        assertArrayEquals(payload, readAll(file))
        
        assertEquals(payload.size.toLong(), AvatarCacheCipher.plaintextLength(file))
    }

    @Test
    fun `keystore unavailable falls back to plaintext write and read`() {
        AvatarCacheCipher.masterKeyProvider = { null } 
        val file = cacheFile()
        val payload = "no-keystore-avatar".toByteArray()
        AvatarCacheCipher.writeEncrypted(file, payload)

        assertNull(AvatarCacheCipher.masterKeyProvider())
        assertFalse("degraded write must be plaintext (no MAGIC)", EncryptedCacheCoder.hasMagic(file))
        assertArrayEquals(payload, file.readBytes())      
        assertArrayEquals(payload, readAll(file))         
    }

    @Test(expected = IOException::class)
    fun `decrypting with a changed key surfaces as IOException`() {
        val file = cacheFile()
        AvatarCacheCipher.writeEncrypted(file, "payload".toByteArray())

        
        
        AvatarCacheCipher.masterKeyProvider = { ByteArray(32) { (it + 1).toByte() } }
        AvatarCacheCipher.openDecrypting(file).use { it.readBytes() }
    }

    @Test
    fun `concurrent writes to the same file stay uncorrupted`() {
        val file = cacheFile()
        val payloadA = ByteArray(64 * 1024) { 0xAA.toByte() }
        val payloadB = ByteArray(64 * 1024) { 0xBB.toByte() }

        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        for (payload in listOf(payloadA, payloadB)) {
            Thread {
                start.await()
                repeat(20) { AvatarCacheCipher.writeEncrypted(file, payload) }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        
        
        val decrypted = readAll(file)
        val isValid = decrypted.contentEquals(payloadA) || decrypted.contentEquals(payloadB)
        assertTrue("final file must decrypt to one intact payload, not a corrupt mix", isValid)

        val leftovers = tempFolder.root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("no temp file should remain after concurrent writes: $leftovers", leftovers.isEmpty())
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
