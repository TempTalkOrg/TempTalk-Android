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

    /**
     * 验证文件的 HMAC 完整性（先验证再解密）
     * @return true 如果 MAC 验证通过
     *
     * Public so callers (e.g. download flow) can perform a one-time integrity check right after
     * download and then read the ciphertext on demand without re-verifying on every access.
     */
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

    /**
     * 精确明文长度：只解密**最后一个密文块**并读取其 PKCS5 pad 字节得到真实明文字节数。
     *
     * 与 [com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil.getPlaintextLength]（按整块
     * 向下取整、恒少算 1–15 字节）不同，本函数返回解密流 / proxy fd **实际吐出的**字节数，用于任何可能被
     * 消费方信任来限定读取范围的元数据（`OpenableColumns.SIZE`、`LocalMedia.size`），避免尾块被截断。
     *
     * 密文布局：`[IV(16)][AES-CBC/PKCS5 密文(16 的倍数)][HMAC(32)]`。最后一块的 IV 是其前一密文块
     * （只有一块时用文件 IV），故仅需读取末尾 1–2 个块即可，不解密整文件。
     *
     * @return 精确明文字节数；若结构非法或 key 无效返回 -1（调用方可回退到 `getPlaintextLength` 估算）。
     */
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

    /**
     * 解密加密文件并保存到目标文件
     * 安全流程：先验证 HMAC，通过后再进行 AES-CBC 解密
     * @param encryptedFile 加密的源文件
     * @param targetFile 解密后的目标文件
     * @param fileKey 加密密钥（64字节 - 32字节用于AES，32字节用于HMAC）
     */
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

        L.i { "[FileDecryptionUtil] 解密完成，文件大小: ${targetFile.length()}" }
    }

    /**
     * 解密加密文件并返回 ByteArray（不写磁盘）
     * 安全流程：先验证 HMAC，通过后再进行 AES-CBC 解密
     * @param encryptedFile 加密的源文件
     * @param fileKey 加密密钥（64字节 - 32字节用于AES，32字节用于HMAC）
     */
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

    /**
     * 返回一个边读边解密的 [InputStream]（不把整文件读入内存）。
     *
     * 适用于较大文件 / 需要流式消费的场景（如 Glide 解码、未来的媒体流）。调用方负责 close()，
     * 关闭时会自动关闭底层文件流。
     *
     * 默认 **不再重复校验 HMAC**（[verifyMacFirst] = false），因为完整性应在下载完成时校验过一次
     * （见 DownloadAttachmentJob）。如需独立使用，可显式传 true。
     *
     * @param encryptedFile 加密的源文件（[IV16][AES-CBC 密文][HMAC32]）
     * @param fileKey 64 字节密钥（前 32 AES，后 32 HMAC）
     */
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

        // CipherInputStream.close() delegates here; ensure the underlying file stream is released.
        override fun close() {
            source.close()
        }
    }
} 