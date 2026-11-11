package com.difft.android.chat.util

import com.difft.android.base.log.lumberjack.L
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FileDecryptionUtil {
    private const val BUFFER_SIZE = 8192
    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32

    





    fun verifyMac(encryptedFile: File, fileKey: ByteArray): Boolean {
        val fileLength = encryptedFile.length()
        if (fileLength < IV_SIZE + MAC_SIZE + 1) return false

        val buffer = ByteArray(BUFFER_SIZE)
        val mac = Mac.getInstance("HmacSHA256")
        val macKeySpec = SecretKeySpec(fileKey, 32, 32, "HmacSHA256")
        mac.init(macKeySpec)

        val dataLength = fileLength - MAC_SIZE
        val storedMac = ByteArray(MAC_SIZE)

        FileInputStream(encryptedFile).use { input ->
            var totalRead = 0L
            while (totalRead < dataLength) {
                val remaining = (dataLength - totalRead).coerceAtMost(BUFFER_SIZE.toLong()).toInt()
                val bytesRead = input.read(buffer, 0, remaining)
                if (bytesRead == -1) break
                mac.update(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            val storedMacBytesRead = input.read(storedMac)
            if (storedMacBytesRead != MAC_SIZE) throw IOException("Incomplete MAC read: expected $MAC_SIZE bytes, got $storedMacBytesRead")
        }

        val computedMac = mac.doFinal()
        return MessageDigest.isEqual(computedMac, storedMac)
    }

    











    fun exactPlaintextLength(encryptedFile: File, fileKey: ByteArray?): Long {
        if (fileKey == null || fileKey.size < 64) return -1
        val cipherLen = encryptedFile.length() - IV_SIZE - MAC_SIZE
        if (cipherLen < IV_SIZE || cipherLen % IV_SIZE != 0L) return -1
        return try {
            RandomAccessFile(encryptedFile, "r").use { raf ->
                val fileIv = ByteArray(IV_SIZE)
                raf.seek(0)
                raf.readFully(fileIv)

                val lastIdx = cipherLen / IV_SIZE - 1
                val iv = if (lastIdx == 0L) fileIv else ByteArray(IV_SIZE).also {
                    raf.seek(IV_SIZE + (lastIdx - 1) * IV_SIZE)
                    raf.readFully(it)
                }
                val lastBlock = ByteArray(IV_SIZE).also {
                    raf.seek(IV_SIZE + lastIdx * IV_SIZE)
                    raf.readFully(it)
                }
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, 0, 32, "AES"), IvParameterSpec(iv))
                val pt = cipher.doFinal(lastBlock)
                val pad = pt[pt.size - 1].toInt() and 0xFF
                val padding = if (pad in 1..IV_SIZE) pad else 0
                cipherLen - padding
            }
        } catch (e: Exception) {
            L.w { "[FileDecryptionUtil] exactPlaintextLength failed: ${e.message}" }
            -1
        }
    }

    






    fun decryptFile(encryptedFile: File, targetFile: File, fileKey: ByteArray?, verifyMacFirst: Boolean = true) {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        if (verifyMacFirst) {
            if (!verifyMac(encryptedFile, fileKey)) {
                L.w { "[FileDecryptionUtil] MAC验证失败，文件可能已被篡改" }
                throw SecurityException("MAC验证失败，文件可能已被篡改")
            }
        }

        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(encryptedFile).use { encryptInputStream ->
            val iv = ByteArray(IV_SIZE)
            val ivBytesRead = encryptInputStream.read(iv)
            if (ivBytesRead != IV_SIZE) throw IOException("Incomplete IV read: expected $IV_SIZE bytes, got $ivBytesRead")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val aesKeySpec = SecretKeySpec(fileKey, 0, 32, "AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, IvParameterSpec(iv))

            val ciphertextLength = encryptedFile.length() - IV_SIZE - MAC_SIZE
            val boundedStream = BoundedInputStream(encryptInputStream, ciphertextLength)
            CipherInputStream(boundedStream, cipher).use { cipherInputStream ->
                try {
                    FileOutputStream(targetFile).use { outputStream ->
                        while (true) {
                            val bytesRead = cipherInputStream.read(buffer)
                            if (bytesRead == -1) break
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                } catch (e: Exception) {
                    targetFile.delete()
                    throw e
                }
            }
        }

        L.i { "[FileDecryptionUtil] decrypted size=${targetFile.length()}" }
    }

    





    fun decryptToBytes(encryptedFile: File, fileKey: ByteArray?, verifyMacFirst: Boolean = true): ByteArray {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        if (verifyMacFirst && !verifyMac(encryptedFile, fileKey)) {
            L.w { "[FileDecryptionUtil] MAC验证失败，文件可能已被篡改" }
            throw SecurityException("MAC验证失败，文件可能已被篡改")
        }

        val buffer = ByteArray(BUFFER_SIZE)
        val outputStream = ByteArrayOutputStream()

        FileInputStream(encryptedFile).use { encryptInputStream ->
            val iv = ByteArray(IV_SIZE)
            val ivBytesRead = encryptInputStream.read(iv)
            if (ivBytesRead != IV_SIZE) throw IOException("Incomplete IV read: expected $IV_SIZE bytes, got $ivBytesRead")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val aesKeySpec = SecretKeySpec(fileKey, 0, 32, "AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, IvParameterSpec(iv))

            val ciphertextLength = encryptedFile.length() - IV_SIZE - MAC_SIZE
            val boundedStream = BoundedInputStream(encryptInputStream, ciphertextLength)
            CipherInputStream(boundedStream, cipher).use { cipherInputStream ->
                while (true) {
                    val bytesRead = cipherInputStream.read(buffer)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        }

        return outputStream.toByteArray()
    }

    









    fun decryptToStream(encryptedFile: File, fileKey: ByteArray?, verifyMacFirst: Boolean = false): InputStream {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        if (verifyMacFirst && !verifyMac(encryptedFile, fileKey)) {
            L.w { "[FileDecryptionUtil] MAC验证失败，文件可能已被篡改" }
            throw SecurityException("MAC验证失败，文件可能已被篡改")
        }

        val encryptInputStream = FileInputStream(encryptedFile)
        try {
            val iv = ByteArray(IV_SIZE)
            val ivBytesRead = encryptInputStream.read(iv)
            if (ivBytesRead != IV_SIZE) throw IOException("Incomplete IV read: expected $IV_SIZE bytes, got $ivBytesRead")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val aesKeySpec = SecretKeySpec(fileKey, 0, 32, "AES")
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, IvParameterSpec(iv))

            val ciphertextLength = encryptedFile.length() - IV_SIZE - MAC_SIZE
            val boundedStream = BoundedInputStream(encryptInputStream, ciphertextLength)
            return CipherInputStream(boundedStream, cipher)
        } catch (e: Exception) {
            encryptInputStream.close()
            throw e
        }
    }

    


    private class BoundedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        
        override fun close() {
            source.close()
        }
    }
}