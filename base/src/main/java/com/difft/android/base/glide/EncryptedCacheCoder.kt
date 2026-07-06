package com.difft.android.base.glide

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts/decrypts Glide RESOURCE cache files. Ported and trimmed from Signal-Android's
 * `EncryptedCoder`.
 *
 * On-disk layout:
 * ```
 * [ MAGIC(16) ][ random(32) ] || AES/CTR( [ MAGIC(16) ] + payload )
 * ```
 * - Per-file content key = HMAC-SHA256([masterKey], random); the IV is all-zero, which is safe under
 *   CTR because every file derives a unique key.
 * - The leading plaintext MAGIC marks the file as ours; the second MAGIC lives inside the ciphertext
 *   so that decrypting with the wrong/changed key (or a corrupt file) yields a mismatch and an
 *   [IOException], which the caller treats as a cache miss (and re-decodes from source).
 *
 * No HMAC over the body: integrity is not required for a local performance cache — tampering simply
 * causes a decode failure → cache miss → reload, which is harmless.
 */
class EncryptedCacheCoder(private val masterKey: ByteArray) {

    @Throws(IOException::class)
    fun encryptedOutput(file: File): OutputStream {
        val random = ByteArray(RANDOM_SIZE).also { SecureRandom().nextBytes(it) }
        val key = newMac().doFinal(random)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, key)

        val fileOut = FileOutputStream(file)
        fileOut.write(MAGIC)
        fileOut.write(random)

        val cipherOut = CipherOutputStream(fileOut, cipher)
        cipherOut.write(MAGIC)
        return cipherOut
    }

    /** @throws IOException on a non-cache file, a key change, corruption, or premature EOF. */
    @Throws(IOException::class)
    fun encryptedInput(file: File): InputStream {
        val fileIn = FileInputStream(file)
        try {
            // Fast reject foreign files (e.g. a plaintext JPEG in Glide's cache) after just the MAGIC,
            // before reading the salt or initializing any crypto — this runs on every File decode.
            val theirMagic = ByteArray(MAGIC.size).also { fileIn.readFully(it) }
            if (!MessageDigest.isEqual(theirMagic, MAGIC)) {
                throw IOException("Not an encrypted cache file")
            }

            val random = ByteArray(RANDOM_SIZE).also { fileIn.readFully(it) }
            val key = newMac().doFinal(random)
            val cipherIn = CipherInputStream(fileIn, newCipher(Cipher.DECRYPT_MODE, key))

            val theirEncryptedMagic = ByteArray(MAGIC.size).also { cipherIn.readFully(it) }
            if (!MessageDigest.isEqual(theirEncryptedMagic, MAGIC)) {
                cipherIn.close()
                throw IOException("Key change on encrypted cache file")
            }
            return cipherIn
        } catch (e: Throwable) {
            runCatching { fileIn.close() }
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun newMac(): Mac =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(masterKey, "HmacSHA256")) }

    private fun newCipher(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(IV_SIZE)))
        }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Premature end of stream")
            offset += read
        }
    }

    companion object {
        private const val RANDOM_SIZE = 32
        private const val IV_SIZE = 16

        /** Bytes of framing overhead: leading MAGIC + random salt + in-ciphertext MAGIC. */
        const val HEADER_OVERHEAD = 16 + RANDOM_SIZE + 16 // 64

        /**
         * Cheap check (reads only the leading [MAGIC]) of whether [file] is one of our encrypted cache
         * files. Used by callers that mix our ciphertext with foreign/legacy plaintext in the same
         * directory (e.g. the avatar cache) to decide between decrypting and reading raw. Never throws.
         */
        fun hasMagic(file: File): Boolean = try {
            FileInputStream(file).use { input ->
                val header = ByteArray(MAGIC.size)
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read == -1) return false
                    offset += read
                }
                MessageDigest.isEqual(header, MAGIC)
            }
        } catch (e: Throwable) {
            false
        }

        // A fixed random sentinel; identical to Signal's so the construction stays battle-tested.
        private val MAGIC = byteArrayOf(
            0x91.toByte(), 0x5e.toByte(), 0x6d.toByte(), 0xb4.toByte(),
            0x09.toByte(), 0xa6.toByte(), 0x68.toByte(), 0xbe.toByte(),
            0xe5.toByte(), 0xb1.toByte(), 0x1b.toByte(), 0xd7.toByte(),
            0x29.toByte(), 0xe5.toByte(), 0x04.toByte(), 0xcc.toByte()
        )
    }
}
