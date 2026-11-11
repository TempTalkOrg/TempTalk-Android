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
