package com.difft.android.chat.common

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupAvatarData
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitFirst
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object GroupAvatarUtil {

    private fun saveToCache(attachmentId: String, bytes: ByteArray) {
        try {
            getNewCacheFile(attachmentId).writeBytes(bytes)
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] saveToCache error: ${e.message}" }
        }
    }

    /**
     * Get existing cache file, checking new format first, then legacy _SMALL format
     */
    fun getCacheFile(attachmentId: String): File? {
        val baseName = "avatar_$attachmentId"
        val cacheDir = FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR)

        // Check new format (no suffix)
        val newFile = File(cacheDir, baseName)
        if (newFile.exists()) return newFile

        // Check legacy format (_SMALL)
        val legacyFile = File(cacheDir, "${baseName}_SMALL")
        if (legacyFile.exists()) return legacyFile

        return null
    }

    /**
     * Get file path for saving new cache (always uses new format without suffix)
     */
    private fun getNewCacheFile(attachmentId: String): File {
        val fileName = "avatar_$attachmentId"
        return File(FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR), fileName)
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun chatHttpClient(): ChativeHttpClient

        @ChativeHttpClientModule.NoHeader
        fun fileClient(): ChativeHttpClient
    }

    /**
     * Fetch and decrypt group avatar from server.
     */
    suspend fun fetchGroupAvatar(context: Context, groupAvatarData: GroupAvatarData): ByteArray {
        try {
            // Get download URL
            val downloadUrlResponse = EntryPointAccessors.fromApplication<EntryPoint>(context)
                .chatHttpClient()
                .httpService
                .getDownloadUrl(SecureSharedPrefsUtil.getBasicAuth(), groupAvatarData.serverId ?: "")
                .await()

            val location = downloadUrlResponse.location
            if (location.isNullOrEmpty()) {
                L.e { "[GroupAvatarUtil] get group avatar location is null" }
                throw NetworkException(message = "get group avatar location is null")
            }

            // Download file content
            val responseBody = EntryPointAccessors.fromApplication<EntryPoint>(context)
                .fileClient()
                .httpService
                .getResponseBody(location, emptyMap(), emptyMap())
                .awaitFirst()

            val bytes = responseBody.bytes()

            // Decrypt data
            val decryptPass: ByteArray = Base64.decode(groupAvatarData.encryptionKey, Base64.DEFAULT)
            val digest: ByteArray = Base64.decode(groupAvatarData.digest, Base64.DEFAULT)
            val decData = decryptGroupAvatar(bytes, decryptPass, digest, groupAvatarData.byteCount?.toIntOrNull() ?: 0)
            return decData

        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] fetchGroupAvatar error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    /**
     * Ensure group avatar is cached. Downloads and decrypts if not already cached.
     * This method is safe to call from any scope - it only handles caching, not UI.
     * @return The cache file if successful, null if failed
     */
    suspend fun ensureCached(context: Context, groupAvatarData: GroupAvatarData): File? = withContext(Dispatchers.IO) {
        val serverId = groupAvatarData.serverId ?: return@withContext null
        try {
            // Check if already cached
            val existingCache = getCacheFile(serverId)
            if (existingCache != null) {
                return@withContext existingCache
            }

            // Download and decrypt
            val bytes = fetchGroupAvatar(context, groupAvatarData)
            
            // Save to cache
            val cacheFile = getNewCacheFile(serverId)
            cacheFile.writeBytes(bytes)
            return@withContext cacheFile
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] ensureCached failed: ${e.message}" }
            // Retry once
            try {
                val bytes = fetchGroupAvatar(context, groupAvatarData)
                val cacheFile = getNewCacheFile(serverId)
                cacheFile.writeBytes(bytes)
                return@withContext cacheFile
            } catch (retryException: Exception) {
                L.e { "[GroupAvatarUtil] ensureCached retry also failed: ${retryException.message}" }
                return@withContext null
            }
        }
    }

    /**
     * Load group avatar with automatic caching
     * - Checks cache first (new format, then legacy _SMALL format)
     * - Downloads and decrypts if not cached
     * - Saves decrypted bytes directly to cache (preserves original format)
     * @return true if avatar loaded successfully, false otherwise
     */
    suspend fun loadGroupAvatar(
        context: Context,
        groupAvatarData: GroupAvatarData,
        imageView: ImageView,
        forceRefresh: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val serverId = groupAvatarData.serverId ?: return@withContext false
        try {
            // 1. Check local cache (new format first, then legacy)
            val cacheFile = getCacheFile(serverId)
            if (!forceRefresh && cacheFile != null) {
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.VISIBLE
                    Glide.with(context)
                        .load(cacheFile)
                        .into(imageView)
                }
                return@withContext true
            }

            // 2. Cache not found, download and decrypt
            val bytes = fetchGroupAvatar(context, groupAvatarData)

            // 3. Save decrypted bytes to cache
            saveToCache(serverId, bytes)

            // 4. Load from cache file and display
            val newCacheFile = getNewCacheFile(serverId)
            withContext(Dispatchers.Main) {
                imageView.visibility = android.view.View.VISIBLE
                Glide.with(context)
                    .load(newCacheFile)
                    .into(imageView)
            }
            return@withContext true
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] loadGroupAvatar first attempt failed: ${e.message}" }
            // Retry once
            try {
                val bytes = fetchGroupAvatar(context, groupAvatarData)
                saveToCache(serverId, bytes)
                val newCacheFile = getNewCacheFile(serverId)
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.VISIBLE
                    Glide.with(context)
                        .load(newCacheFile)
                        .into(imageView)
                }
                return@withContext true
            } catch (retryException: Exception) {
                L.e { "[GroupAvatarUtil] loadGroupAvatar retry also failed: ${retryException.message}" }
                return@withContext false
            }
        }
    }


    private fun decryptGroupAvatar(
        dataToDecrypt: ByteArray,
        key: ByteArray,
        digest: ByteArray?,
        paddedSize: Int
    ): ByteArray {
        // 检查 digest 是否为空
        if (digest == null || digest.isEmpty()) {
            throw IllegalArgumentException("Digest is required for decryption.")
        }

        // 检查数据长度是否满足最小要求
        val ivLength = 16 // IV 的长度
        val macLength = 32 // HMAC-SHA256 输出长度
        if (dataToDecrypt.size < ivLength + macLength) {
            throw IllegalArgumentException("Message shorter than crypto overhead!")
        }

        // 提取 AES 密钥和 HMAC 密钥
        val aesKey = key.copyOfRange(0, 32) // AES 密钥 32 字节
        val hmacKey = key.copyOfRange(32, 64) // HMAC 密钥 32 字节

        // 提取 IV、加密数据和 HMAC 校验值
        val iv = dataToDecrypt.copyOfRange(0, ivLength)
        val encryptedData = dataToDecrypt.copyOfRange(ivLength, dataToDecrypt.size - macLength)
        val providedHmac = dataToDecrypt.copyOfRange(dataToDecrypt.size - macLength, dataToDecrypt.size)

        // 使用 HMAC 验证数据完整性
        val mac = Mac.getInstance("HmacSHA256")
        val macKeySpec = SecretKeySpec(hmacKey, "HmacSHA256")
        mac.init(macKeySpec)
        val calculatedHmac = mac.doFinal(dataToDecrypt.copyOfRange(0, ivLength + encryptedData.size))

        // 如果计算的 HMAC 和提供的 HMAC 不匹配，则验证失败
        if (!MessageDigest.isEqual(providedHmac, calculatedHmac)) {
            throw SecurityException("MAC validation failed. Data integrity is compromised.")
        }

        // 使用 AES CBC 模式解密数据
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val paddedPlainText = cipher.doFinal(encryptedData)

        // Return decrypted data directly if no unpaddedSize specified
        if (paddedSize == 0) {
            return paddedPlainText
        }

        // 恢复未填充的数据
        if (paddedSize > paddedPlainText.size) {
            throw IllegalArgumentException("Unpadded size is greater than padded data length.")
        }

        // 如果解密后的数据和 unpaddedSize 相同，则返回整个数据
        return if (paddedSize == paddedPlainText.size) {
            paddedPlainText
        } else {
            // 否则，返回前 unpaddedSize 字节数据
            paddedPlainText.copyOfRange(0, paddedSize)
        }
    }

    fun encryptGroupAvatar(
        dataToEncrypt: ByteArray,
        keySize: Int = 64 // AES 密钥和 HMAC 密钥总大小（字节）
    ): Map<String, Any> {
        // 生成随机密钥和 HMAC 密钥
        val key = ByteArray(keySize)
        SecureRandom().nextBytes(key)
        val aesKey = key.copyOfRange(0, 32) // 前 32 字节作为 AES 密钥
        val hmacKey = key.copyOfRange(32, 64) // 后 32 字节作为 HMAC 密钥

        // 生成随机 IV
        val iv = ByteArray(16) // AES CBC 模式的 IV 长度固定为 16 字节
        SecureRandom().nextBytes(iv)

        // 使用 AES 进行加密
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(dataToEncrypt)

        // 拼接 IV 和加密数据
        val encryptedPayload = iv + encryptedData

        // 计算 HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val hmac = mac.doFinal(encryptedPayload)

        // 拼接完整加密数据（IV + 加密数据 + HMAC）
        val finalEncryptedData = encryptedPayload + hmac

        // 计算摘要
        val digest = MessageDigest.getInstance("SHA-256").digest(finalEncryptedData)
        val digestBase64 = Base64.encodeToString(digest, Base64.NO_WRAP)

        // 编码密钥为 Base64
        val encryptionKeyBase64 = Base64.encodeToString(key, Base64.NO_WRAP)

        // 返回加密数据和相关信息
        return mapOf(
            "encryptionKey" to encryptionKeyBase64, // 用于服务端解密的密钥
            "digest" to digestBase64,               // 用于服务端验证完整性的摘要
            "encryptedData" to finalEncryptedData   // 最终加密数据，需上传到服务器
        )
    }

    /**
     * Generate group avatar file (PNG), returns file path.
     */
    fun generateAvatarFile(items: List<LetterItem>, backgroundColor: Int): String? {
        val dirPath = FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR)
        val fileName = "${System.currentTimeMillis()}.png"
        val file = File(dirPath, fileName)

        return try {
            val bitmap = GroupAvatarGenerator.generate(items, backgroundColor)
            file.outputStream().use { output ->
                BufferedOutputStream(output).use { bos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    bos.flush()
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] generateAvatarFile error: ${e.stackTraceToString()}" }
            null
        }
    }
}