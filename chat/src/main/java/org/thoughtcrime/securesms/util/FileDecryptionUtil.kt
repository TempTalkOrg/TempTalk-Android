package org.thoughtcrime.securesms.util

import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
     * 解密加密文件并保存到目标文件
     * @param encryptedFile 加密的源文件
     * @param targetFile 解密后的目标文件
     * @param fileKey 加密密钥（64字节 - 32字节用于AES，32字节用于HMAC）
     */
    fun decryptFile(encryptedFile: File, targetFile: File, fileKey: ByteArray?) {
        if (!encryptedFile.exists()) {
            throw IOException("encrypted File is not exist: ${encryptedFile.absolutePath}")
        }
        if (fileKey == null || fileKey.isEmpty()) {
            throw IllegalArgumentException("fileKey is null or empty")
        }

        val buffer = ByteArray(BUFFER_SIZE)
        val encryptInputStream = FileInputStream(encryptedFile)
        try {
            val iv = ByteArray(IV_SIZE)
            encryptInputStream.read(iv)

            // 初始化解密器
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val aesKeySpec = SecretKeySpec(fileKey, 0, 32, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, ivParameterSpec)

            // 创建 Mac
            val mac = Mac.getInstance("HmacSHA256")
            val macKeySpec = SecretKeySpec(fileKey, 32, 32, "HmacSHA256")
            mac.init(macKeySpec)

            val realOutputStream = FileOutputStream(targetFile)

            // 创建解密输入流
            val cipherInputStream = CipherInputStream(encryptInputStream, cipher)

            try {
                var totalBytesRead1: Long = 0
                val remainingBytes = encryptedFile.length() - MAC_SIZE - IV_SIZE
                var bytesRead = 0

                while (remainingBytes - totalBytesRead1 > 512) {
                    bytesRead = cipherInputStream.read(buffer)
                    if (bytesRead == -1) break
                    realOutputStream.write(buffer, 0, bytesRead)
                    totalBytesRead1 += bytesRead
                }

                val skipBytes = encryptInputStream.available() - MAC_SIZE
                var totalBytesRead2 = 0L
                while (totalBytesRead2 < skipBytes) {
                    bytesRead = encryptInputStream.read(buffer)
                    if (bytesRead == -1) break
                    val bytesToRead2 = buffer.size.toLong().coerceAtMost(skipBytes - totalBytesRead2)
                    val bytes = cipher.doFinal(buffer, 0, bytesToRead2.toInt())
                    realOutputStream.write(bytes)
                    totalBytesRead2 += bytesToRead2
                }
            } finally {
                encryptInputStream.close()
                cipherInputStream.close()
                realOutputStream.close()
            }

            // 计算MAC
            val encryptInputStream3 = FileInputStream(encryptedFile)
            encryptInputStream3.use {
                val skipBytes3 = encryptedFile.length() - MAC_SIZE
                var totalBytesRead3 = 0L
                var bytesRead = 0
                while (totalBytesRead3 < skipBytes3) {
                    bytesRead = it.read(buffer)
                    if (bytesRead == -1) break
                    val bytesToRead3 = buffer.size.toLong().coerceAtMost(skipBytes3 - totalBytesRead3)
                    mac.update(buffer, 0, bytesToRead3.toInt())
                    totalBytesRead3 += bytesToRead3
                }
            }

            // 读取并验证 Mac
            val macDigest = mac.doFinal()

            val encryptInputStream2 = FileInputStream(encryptedFile)
            encryptInputStream2.use {
                it.skip(encryptedFile.length() - MAC_SIZE)
                val macFromStream = ByteArray(macDigest.size)
                it.read(macFromStream)

                if (!macDigest.contentEquals(macFromStream)) {
                    L.w { "[FileDecryptionUtil] MAC验证失败，文件可能已被篡改" }
                    throw Exception("MAC验证失败，文件可能已被篡改")
                } else {
                    L.i { "[FileDecryptionUtil] MAC验证成功，文件是完整的" }
                    L.d { "[FileDecryptionUtil] 解密后文件大小: ${targetFile.length()}" }
                }
            }
        } finally {
            encryptInputStream.close()
        }
    }
} 