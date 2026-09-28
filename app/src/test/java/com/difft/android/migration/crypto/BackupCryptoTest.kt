package com.difft.android.migration.crypto

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BackupCryptoImpl].
 *
 * Robolectric is only required because the impl is provided into Hilt; the methods
 * themselves are pure-JVM JCE. Using JUnit + JCE keeps these tests fast.
 *
 * Coverage:
 *  - KDF determinism: equal `(passphrase, salt)` → equal enc/mac material.
 *  - IV uniqueness: 100 invocations of `randomBytes(16)` produce distinct values.
 *  - constant-time compare: equal / unequal / mid-byte difference.
 *  - Header round-trip: build → parse returns same fields.
 *  - Body prefix round-trip: build → parse returns same fields.
 */
@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {

    private val crypto: BackupCrypto = BackupCryptoImpl()

    // ─── KDF ────────────────────────────────────────────────────────────────

    @Test
    fun `deriveKeys is deterministic for same passphrase and salt`() {
        val passphrase = "QWERTY-test-passphrase-1234567890-abcd"
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }

        val a = crypto.deriveKeys(passphrase, salt)
        val b = crypto.deriveKeys(passphrase, salt)

        // SecretKeySpec doesn't override equals on encoded() bytes, so compare encoded.
        assertContentEquals(a.encKey.encoded, b.encKey.encoded)
        assertContentEquals(a.macKey.encoded, b.macKey.encoded)
        assertEquals(32, a.encKey.encoded.size)
        assertEquals(32, a.macKey.encoded.size)
        assertEquals("AES", a.encKey.algorithm)
        assertEquals("HmacSHA256", a.macKey.algorithm)
    }

    @Test
    fun `deriveKeys differs when salt differs`() {
        val passphrase = "same-passphrase"
        val salt1 = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }
        val salt2 = ByteArray(BackupCrypto.SALT_BYTES) { (it + 1).toByte() }

        val a = crypto.deriveKeys(passphrase, salt1)
        val b = crypto.deriveKeys(passphrase, salt2)

        assertFalse(a.encKey.encoded.contentEquals(b.encKey.encoded))
        assertFalse(a.macKey.encoded.contentEquals(b.macKey.encoded))
    }

    @Test
    fun `deriveKeys differs when passphrase differs`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }
        val a = crypto.deriveKeys("phrase-a", salt)
        val b = crypto.deriveKeys("phrase-b", salt)
        assertFalse(a.encKey.encoded.contentEquals(b.encKey.encoded))
        assertFalse(a.macKey.encoded.contentEquals(b.macKey.encoded))
    }

    @Test
    fun `deriveKeys rejects wrong-size salt`() {
        assertFailsWith<IllegalArgumentException> {
            crypto.deriveKeys("any", ByteArray(BackupCrypto.SALT_BYTES - 1))
        }
    }

    // ─── randomBytes / IV uniqueness ────────────────────────────────────────

    @Test
    fun `randomBytes returns size and is unique across N invocations`() {
        val size = BackupCrypto.IV_BYTES
        val samples = HashSet<String>(100)
        repeat(100) {
            val bytes = crypto.randomBytes(size)
            assertEquals(size, bytes.size)
            samples.add(bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) })
        }
        // Probability of any collision in 100 16-byte samples from a CSPRNG is astronomically
        // small (< 2^-100). If this ever fails, the RNG is broken — not flaky.
        assertEquals(100, samples.size, "All 100 random 16-byte samples should be unique")
    }

    @Test
    fun `randomBytes rejects non-positive size`() {
        assertFailsWith<IllegalArgumentException> { crypto.randomBytes(0) }
        assertFailsWith<IllegalArgumentException> { crypto.randomBytes(-1) }
    }

    // ─── constantTimeEquals ─────────────────────────────────────────────────

    @Test
    fun `constantTimeEquals true for equal arrays`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { it.toByte() }
        assertTrue(crypto.constantTimeEquals(a, b))
    }

    @Test
    fun `constantTimeEquals false for arrays differing in mid byte`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { it.toByte() }
        b[16] = (b[16] + 1).toByte()
        assertFalse(crypto.constantTimeEquals(a, b))
    }

    @Test
    fun `constantTimeEquals false for arrays differing in first byte`() {
        val a = ByteArray(32) { it.toByte() }
        val b = a.copyOf()
        b[0] = (b[0] + 1).toByte()
        assertFalse(crypto.constantTimeEquals(a, b))
    }

    @Test
    fun `constantTimeEquals false for arrays differing in last byte`() {
        val a = ByteArray(32) { it.toByte() }
        val b = a.copyOf()
        b[31] = (b[31] + 1).toByte()
        assertFalse(crypto.constantTimeEquals(a, b))
    }

    @Test
    fun `constantTimeEquals false for different length arrays`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(33) { 1 }
        assertFalse(crypto.constantTimeEquals(a, b))
    }

    // ─── Cipher / Mac construction ──────────────────────────────────────────

    @Test
    fun `newAesCtrCipher round-trips a known plaintext`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { 7 }
        val iv = ByteArray(BackupCrypto.IV_BYTES) { 9 }
        val keys = crypto.deriveKeys("phrase-x", salt)

        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val enc = crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, iv)
        val ct = enc.doFinal(plaintext)
        assertEquals(plaintext.size, ct.size, "CTR mode does not pad")

        val dec = crypto.newAesCtrCipher(Cipher.DECRYPT_MODE, keys.encKey, iv)
        val pt = dec.doFinal(ct)
        assertContentEquals(plaintext, pt)
    }

    @Test
    fun `newAesCtrCipher rejects wrong-size iv`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { 7 }
        val keys = crypto.deriveKeys("p", salt)
        assertFailsWith<IllegalArgumentException> {
            crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, ByteArray(BackupCrypto.IV_BYTES - 1))
        }
    }

    @Test
    fun `newHmacSha256 returns initialised Mac`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { 1 }
        val keys = crypto.deriveKeys("p", salt)
        val mac = crypto.newHmacSha256(keys.macKey)
        mac.update("payload".toByteArray())
        val out = mac.doFinal()
        assertEquals(32, out.size, "HmacSHA256 yields 32-byte tag")
    }

    // ─── Header round-trip ──────────────────────────────────────────────────

    @Test
    fun `buildHeader then parseHeader round-trips`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { (it * 3).toByte() }
        val iv = ByteArray(BackupCrypto.IV_BYTES) { (it * 5).toByte() }
        val raw = crypto.buildHeader(salt, iv)
        assertEquals(BackupCrypto.HEADER_SIZE, raw.size)

        val parsed = crypto.parseHeader(raw)
        assertEquals(BackupCrypto.VERSION, parsed.version)
        assertEquals(BackupCrypto.KDF_ITERATIONS, parsed.kdfIterations)
        assertContentEquals(salt, parsed.salt)
        assertContentEquals(iv, parsed.iv)
    }

    @Test
    fun `parseHeader rejects bad magic`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES)
        val iv = ByteArray(BackupCrypto.IV_BYTES)
        val raw = crypto.buildHeader(salt, iv)
        raw[0] = 'X'.code.toByte() // corrupt magic
        assertFailsWith<IllegalArgumentException> { crypto.parseHeader(raw) }
    }

    @Test
    fun `parseHeader rejects wrong size`() {
        assertFailsWith<IllegalArgumentException> {
            crypto.parseHeader(ByteArray(BackupCrypto.HEADER_SIZE - 1))
        }
    }

    // ─── Body prefix round-trip ─────────────────────────────────────────────

    @Test
    fun `buildBodyPrefix then parseBodyPrefix round-trips`() {
        val key = ByteArray(BackupCrypto.PLAINTEXT_KEY_SIZE) { (it + 1).toByte() }
        val dbLen = 1_234_567_890L
        val raw = crypto.buildBodyPrefix(key, dbLen)
        assertEquals(BackupCrypto.BODY_PREFIX_SIZE, raw.size)

        val parsed = crypto.parseBodyPrefix(raw)
        assertContentEquals(key, parsed.cipherKey)
        assertEquals(dbLen, parsed.dbLen)
    }

    @Test
    fun `parseBodyPrefix rejects negative dbLen`() {
        val key = ByteArray(BackupCrypto.PLAINTEXT_KEY_SIZE) { 0 }
        val raw = crypto.buildBodyPrefix(key, 0L)
        // Corrupt the dbLen field (last 8 bytes) to 0xFFFFFFFFFFFFFFFF = -1L.
        for (i in 0 until 8) raw[BackupCrypto.BODY_PREFIX_SIZE - 1 - i] = 0xFF.toByte()
        assertFailsWith<IllegalArgumentException> { crypto.parseBodyPrefix(raw) }
    }

    @Test
    fun `buildBodyPrefix rejects wrong cipher key size`() {
        assertFailsWith<IllegalArgumentException> {
            crypto.buildBodyPrefix(ByteArray(BackupCrypto.PLAINTEXT_KEY_SIZE - 1), 0L)
        }
    }

    @Test
    fun `buildBodyPrefix rejects negative dbLen`() {
        val key = ByteArray(BackupCrypto.PLAINTEXT_KEY_SIZE) { 0 }
        assertFailsWith<IllegalArgumentException> {
            crypto.buildBodyPrefix(key, -1L)
        }
    }

    @Test
    fun `header constants match design report`() {
        assertEquals(42, BackupCrypto.HEADER_SIZE, "design §2.1 header = 42B")
        assertEquals(58, BackupCrypto.BODY_PREFIX_SIZE, "design §2.1 body prefix = 58B")
        assertEquals(32, BackupCrypto.HMAC_BYTES, "design §2.1 HMAC = 32B")
        assertEquals(48, BackupCrypto.PLAINTEXT_KEY_SIZE, "design §2.1 cipher key = 48B")
        assertEquals(250_000, BackupCrypto.KDF_ITERATIONS, "design §3.1 KDF iter = 250k")
        assertEquals("TTBK", BackupCrypto.MAGIC, "design §2.1 magic = TTBK")
        assertEquals(0x0001.toShort(), BackupCrypto.VERSION, "design §2.1 version = 0x0001")
        // Sanity: header bytes should produce the expected size.
        val raw = BackupCryptoImpl().buildHeader(
            ByteArray(BackupCrypto.SALT_BYTES),
            ByteArray(BackupCrypto.IV_BYTES),
        )
        assertNotNull(raw)
        assertEquals(BackupCrypto.HEADER_SIZE, raw.size)
    }
}
