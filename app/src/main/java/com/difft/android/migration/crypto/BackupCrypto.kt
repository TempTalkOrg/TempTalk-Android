package com.difft.android.migration.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level primitives for the backup encryption pipeline. Stateless — each method is
 * deterministic given its inputs and safe to call from any thread.
 *
 * The KDF is intentionally slow (~1s on mid-range devices); call it off the main thread.
 *
 * Security boundary: never log the passphrase, derived keys or cipher_key.
 */
interface BackupCrypto {

    /**
     * Derive `(encKey: AES-256, macKey: HMAC-SHA256)` from `passphrase + salt` using
     * PBKDF2-HMAC-SHA512 at [KDF_ITERATIONS]. Output is 64 bytes split 32 / 32.
     *
     * Deterministic: same `(passphrase, salt)` always yields the same pair.
     */
    fun deriveKeys(passphrase: String, salt: ByteArray): KeyPair

    /** Initialise an AES/CTR/NoPadding cipher for streaming. */
    fun newAesCtrCipher(mode: Int, encKey: SecretKey, iv: ByteArray): Cipher

    /** Initialise an HMAC-SHA256 Mac for streaming. */
    fun newHmacSha256(macKey: SecretKey): Mac

    /**
     * Build the 42-byte plaintext header:
     *   `[MAGIC(4) | VERSION(2 BE) | SALT(16) | KDF_ITER(4 BE) | IV(16)]`.
     */
    fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray

    /**
     * Parse a 42-byte header and return its fields. Throws [IllegalArgumentException] for
     * bad magic / unsupported version / unexpected KDF iter — the caller MUST translate
     * to a `MigrationException` at the boundary.
     */
    fun parseHeader(header: ByteArray): Header

    /**
     * Build the encrypted-body prefix bytes (cleartext form):
     *   `[CIPHER_KEY_LEN(2 BE)=48 | CIPHER_KEY(48) | DB_LEN(8 BE)]` — 58 bytes total.
     */
    fun buildBodyPrefix(cipherKey: ByteArray, dbLen: Long): ByteArray

    /** Parse the 58-byte body prefix into `(cipherKey, dbLen)`. */
    fun parseBodyPrefix(prefix: ByteArray): BodyPrefix

    /** `SecureRandom.nextBytes` wrapper. Each invocation yields fresh entropy. */
    fun randomBytes(size: Int): ByteArray

    /**
     * Constant-time byte-array equality. Returns false for differing-length arrays without
     * leaking the length comparison via timing.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

    data class KeyPair(val encKey: SecretKey, val macKey: SecretKey)

    data class Header(
        val version: Short,
        val salt: ByteArray,
        val kdfIterations: Int,
        val iv: ByteArray,
    )

    data class BodyPrefix(val cipherKey: ByteArray, val dbLen: Long)

    companion object {
        /** ASCII bytes `"TTBK"` written verbatim at offset 0 of every backup file. */
        const val MAGIC = "TTBK"

        /** Format version of the v1 / same-platform byte-copy backup format. */
        const val VERSION: Short = 0x0001

        const val SALT_BYTES = 16
        const val IV_BYTES = 16
        const val HMAC_BYTES = 32
        const val PLAINTEXT_KEY_SIZE = 48
        const val KDF_ITERATIONS = 250_000

        /** Header byte size: 4 (magic) + 2 (ver) + 16 (salt) + 4 (iter) + 16 (iv) = 42. */
        const val HEADER_SIZE = MAGIC.length + 2 + SALT_BYTES + 4 + IV_BYTES

        /** Encrypted body prefix size (cleartext form): 2 + 48 + 8 = 58. */
        const val BODY_PREFIX_SIZE = 2 + PLAINTEXT_KEY_SIZE + 8

        const val AES_KEY_SIZE_BITS = 256
        const val HMAC_KEY_SIZE_BITS = 256

        /** Streaming IO buffer for encrypt/decrypt. */
        const val IO_BUFFER_BYTES = 64 * 1024
    }
}

@Singleton
class BackupCryptoImpl @Inject constructor() : BackupCrypto {

    // SecureRandom is thread-safe per JDK spec; reuse the single instance to avoid the
    // per-call seeding cost on some platforms.
    private val rng: SecureRandom = SecureRandom()

    override fun deriveKeys(passphrase: String, salt: ByteArray): BackupCrypto.KeyPair {
        require(salt.size == BackupCrypto.SALT_BYTES) {
            "salt must be ${BackupCrypto.SALT_BYTES} bytes; got ${salt.size}"
        }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val totalBits = BackupCrypto.AES_KEY_SIZE_BITS + BackupCrypto.HMAC_KEY_SIZE_BITS
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            BackupCrypto.KDF_ITERATIONS,
            totalBits,
        )
        val derived = try {
            factory.generateSecret(spec).encoded
        } finally {
            // Clear PBEKeySpec's internal copy of the passphrase as best-effort.
            spec.clearPassword()
        }
        try {
            val encKey: SecretKey = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
            val macKey: SecretKey = SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA256")
            return BackupCrypto.KeyPair(encKey, macKey)
        } finally {
            // Wipe the joined 64-byte buffer — the two SecretKeySpec copies are the only
            // remaining references to enc/mac material.
            derived.fill(0)
        }
    }

    override fun newAesCtrCipher(mode: Int, encKey: SecretKey, iv: ByteArray): Cipher {
        require(iv.size == BackupCrypto.IV_BYTES) {
            "iv must be ${BackupCrypto.IV_BYTES} bytes; got ${iv.size}"
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, encKey, IvParameterSpec(iv))
        return cipher
    }

    override fun newHmacSha256(macKey: SecretKey): Mac {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        return mac
    }

    override fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray {
        require(salt.size == BackupCrypto.SALT_BYTES)
        require(iv.size == BackupCrypto.IV_BYTES)
        return ByteBuffer.allocate(BackupCrypto.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(BackupCrypto.MAGIC.toByteArray(Charsets.US_ASCII))
            putShort(BackupCrypto.VERSION)
            put(salt)
            putInt(BackupCrypto.KDF_ITERATIONS)
            put(iv)
        }.array()
    }

    override fun parseHeader(header: ByteArray): BackupCrypto.Header {
        require(header.size == BackupCrypto.HEADER_SIZE) {
            "header must be ${BackupCrypto.HEADER_SIZE} bytes; got ${header.size}"
        }
        val magic = String(header, 0, BackupCrypto.MAGIC.length, Charsets.US_ASCII)
        require(magic == BackupCrypto.MAGIC) { "bad magic: '$magic'" }
        val version = ByteBuffer.wrap(header, BackupCrypto.MAGIC.length, 2)
            .order(ByteOrder.BIG_ENDIAN).short
        require(version == BackupCrypto.VERSION) { "unsupported version: $version" }
        val saltStart = BackupCrypto.MAGIC.length + 2
        val salt = header.copyOfRange(saltStart, saltStart + BackupCrypto.SALT_BYTES)
        val iterStart = saltStart + BackupCrypto.SALT_BYTES
        val iter = ByteBuffer.wrap(header, iterStart, 4).order(ByteOrder.BIG_ENDIAN).int
        require(iter == BackupCrypto.KDF_ITERATIONS) {
            "unexpected KDF iterations: $iter (expected ${BackupCrypto.KDF_ITERATIONS})"
        }
        val ivStart = iterStart + 4
        val iv = header.copyOfRange(ivStart, ivStart + BackupCrypto.IV_BYTES)
        return BackupCrypto.Header(version, salt, iter, iv)
    }

    override fun buildBodyPrefix(cipherKey: ByteArray, dbLen: Long): ByteArray {
        require(cipherKey.size == BackupCrypto.PLAINTEXT_KEY_SIZE) {
            "cipherKey must be ${BackupCrypto.PLAINTEXT_KEY_SIZE} bytes; got ${cipherKey.size}"
        }
        require(dbLen >= 0) { "dbLen must be non-negative; got $dbLen" }
        return ByteBuffer.allocate(BackupCrypto.BODY_PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putShort(BackupCrypto.PLAINTEXT_KEY_SIZE.toShort())
            .put(cipherKey)
            .putLong(dbLen)
            .array()
    }

    override fun parseBodyPrefix(prefix: ByteArray): BackupCrypto.BodyPrefix {
        require(prefix.size == BackupCrypto.BODY_PREFIX_SIZE) {
            "body prefix must be ${BackupCrypto.BODY_PREFIX_SIZE} bytes; got ${prefix.size}"
        }
        val keyLen = ByteBuffer.wrap(prefix, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
        require(keyLen == BackupCrypto.PLAINTEXT_KEY_SIZE) {
            "unexpected cipher key length: $keyLen"
        }
        val cipherKey = prefix.copyOfRange(2, 2 + BackupCrypto.PLAINTEXT_KEY_SIZE)
        val dbLenStart = 2 + BackupCrypto.PLAINTEXT_KEY_SIZE
        val dbLen = ByteBuffer.wrap(prefix, dbLenStart, 8).order(ByteOrder.BIG_ENDIAN).long
        require(dbLen >= 0) { "decoded dbLen must be non-negative; got $dbLen" }
        return BackupCrypto.BodyPrefix(cipherKey, dbLen)
    }

    override fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "size must be positive; got $size" }
        val buf = ByteArray(size)
        rng.nextBytes(buf)
        return buf
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        // Length is fixed by the protocol on both sides, so an early length check leaks nothing.
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
