package com.difft.android.chat.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a plaintext file into the at-rest ciphertext format consumed by [FileDecryptionUtil] and
 * [com.difft.android.chat.media.EncryptedAttachmentProvider].
 *
 * Output layout (identical to the sender-side upload encryption in PushTextSendJob):
 * `[IV(16)][AES-CBC/PKCS5 ciphertext][HMAC-SHA256(32)]`, where the 64-byte [fileKey] splits into
 * `key[0:32]` (AES) and `key[32:64]` (HMAC). The HMAC covers `IV || ciphertext`.
 *
 * Used by the one-time legacy-plaintext migration to re-encrypt images/voice that were decrypted to
 * disk before the encrypted-at-rest change.
 */
object FileEncryptionUtil {
    private const val BUFFER_SIZE = 8192
    private const val IV_SIZE = 16

    fun encryptFile(plainFile: File, encryptedFile: File, fileKey: ByteArray?) {
        if (!plainFile.exists()) {
            throw IOException("plain file does not exist: ${plainFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, 0, 32, "AES"), IvParameterSpec(iv))

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(fileKey, 32, 32, "HmacSHA256"))
        mac.update(iv)

        val buffer = ByteArray(BUFFER_SIZE)
        FileOutputStream(encryptedFile).use { out ->
            out.write(iv)
            FileInputStream(plainFile).use { plainIn ->
                CipherInputStream(plainIn, cipher).use { cipherIn ->
                    while (true) {
                        val read = cipherIn.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        mac.update(buffer, 0, read)
                    }
                }
            }
            out.write(mac.doFinal())
            out.flush()
            out.fd.sync()
        }
    }
}
