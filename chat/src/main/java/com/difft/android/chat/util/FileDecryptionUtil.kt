package com.difft.android.chat.util

import com.difft.android.base.log.lumberjack.L
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
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

    /**
     * 验证文件的 HMAC 完整性（先验证再解密）
     * @return true 如果 MAC 验证通过
     */
    private fun verifyMac(encryptedFile: File, fileKey: ByteArray): Boolean {
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

    /**
     * 解密加密文件并保存到目标文件
     * 安全流程：先验证 HMAC，通过后再进行 AES-CBC 解密
     * @param encryptedFile 加密的源文件
     * @param targetFile 解密后的目标文件
     * @param fileKey 加密密钥（64字节 - 32字节用于AES，32字节用于HMAC）
     */
    fun decryptFile(encryptedFile: File, targetFile: File, fileKey: ByteArray?) {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        if (!verifyMac(encryptedFile, fileKey)) {
            L.w { "[FileDecryptionUtil] MAC验证失败，文件可能已被篡改" }
            throw SecurityException("MAC验证失败，文件可能已被篡改")
        }
        L.i { "[FileDecryptionUtil] MAC验证成功，开始解密" }

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

        L.i { "[FileDecryptionUtil] 解密完成，文件大小: ${targetFile.length()}" }
    }

    /**
     * 解密加密文件并返回 ByteArray（不写磁盘）
     * 安全流程：先验证 HMAC，通过后再进行 AES-CBC 解密
     * @param encryptedFile 加密的源文件
     * @param fileKey 加密密钥（64字节 - 32字节用于AES，32字节用于HMAC）
     */
    fun decryptToBytes(encryptedFile: File, fileKey: ByteArray?): ByteArray {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.size < 64) {
            throw IllegalArgumentException("fileKey must be 64 bytes (got ${fileKey?.size ?: 0})")
        }

        if (!verifyMac(encryptedFile, fileKey)) {
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

    /**
     * 限制从底层流读取的最大字节数，防止 CipherInputStream 读入 MAC 尾部区域
     */
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
    }
} 