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









interface BackupCrypto {

    





    fun deriveKeys(passphrase: String, salt: ByteArray): KeyPair

    
    fun newAesCtrCipher(mode: Int, encKey: SecretKey, iv: ByteArray): Cipher

    
    fun newHmacSha256(macKey: SecretKey): Mac

    



    fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray

    




    fun parseHeader(header: ByteArray): Header

    



    fun buildBodyPrefix(cipherKey: ByteArray, dbLen: Long): ByteArray

    
    fun parseBodyPrefix(prefix: ByteArray): BodyPrefix

    
    fun randomBytes(size: Int): ByteArray

    



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
        
        const val MAGIC = "TTBK"

        
        const val VERSION: Short = 0x0001

        const val SALT_BYTES = 16
        const val IV_BYTES = 16
        const val HMAC_BYTES = 32
        const val PLAINTEXT_KEY_SIZE = 48
        const val KDF_ITERATIONS = 250_000

        
        const val HEADER_SIZE = MAGIC.length + 2 + SALT_BYTES + 4 + IV_BYTES

        
        const val BODY_PREFIX_SIZE = 2 + PLAINTEXT_KEY_SIZE + 8

        const val AES_KEY_SIZE_BITS = 256
        const val HMAC_KEY_SIZE_BITS = 256

        
        const val IO_BUFFER_BYTES = 64 * 1024
    }
}

@Singleton
class BackupCryptoImpl @Inject constructor() : BackupCrypto {

    
    
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
            
            spec.clearPassword()
        }
        try {
            val encKey: SecretKey = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
            val macKey: SecretKey = SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA256")
            return BackupCrypto.KeyPair(encKey, macKey)
        } finally {
            
            
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
        
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
