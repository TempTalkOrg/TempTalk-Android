package com.difft.android.chat.common

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.dp
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupAvatarData
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun saveBitmapFile(attachmentId: String, bitmap: Bitmap, size: AvatarCacheSize) {
        withContext(Dispatchers.IO) {
            try {
                val bos = BufferedOutputStream(FileOutputStream(getGroupAvatarFile(attachmentId, size)))
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos)
                bos.flush()
                bos.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    enum class AvatarCacheSize {
        BIG, SMALL
    }

    fun getGroupAvatarFile(attachmentId: String, size: AvatarCacheSize): File {
        val fileName = "avatar_${attachmentId}_${size}"
        val filePath = "${FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR)}$fileName"
        return File(filePath)
    }

    // 加载图片并保存到本地
    suspend fun loadAndSaveBitmap(context: Context, content: Any, attachmentId: String, imageView: ImageView, size: AvatarCacheSize) {
        // 获取 ImageView 尺寸，如果没有则使用默认值
        val targetWidth = if (imageView.width > 0) imageView.width else {
            when (size) {
                AvatarCacheSize.SMALL -> 50.dp
                AvatarCacheSize.BIG -> -1
            }
        }

        val targetHeight = if (imageView.height > 0) imageView.height else {
            when (size) {
                AvatarCacheSize.SMALL -> 50.dp
                AvatarCacheSize.BIG -> -1
            }
        }

        L.d { "[GroupAvatarUtil] loadAndSaveBitmap targetWidth:${targetWidth} targetHeight:${targetHeight}" }

        // 直接获取调整后的 Bitmap（已经在 IO 线程中，不需要 withContext）
        val bitmap = Glide.with(context)
            .asBitmap()
            .load(content)
            .submit(targetWidth, targetHeight)
            .get()

        // 保存到本地
        saveBitmapFile(attachmentId, bitmap, size)

        // 设置到 ImageView
        withContext(Dispatchers.Main) {
            imageView.visibility = android.view.View.VISIBLE
            imageView.setImageBitmap(bitmap)
        }
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun chatHttpClient(): ChativeHttpClient

        @ChativeHttpClientModule.NoHeader
        fun fileClient(): ChativeHttpClient
    }

    // 重构为协程版本
    suspend fun fetchGroupAvatar(context: Context, groupAvatarData: GroupAvatarData): ByteArray {
        try {
            // 1. 获取下载 URL
            val downloadUrlResponse = EntryPointAccessors.fromApplication<EntryPoint>(context)
                .chatHttpClient()
                .httpService
                .getDownloadUrl(SecureSharedPrefsUtil.getBasicAuth(), groupAvatarData.serverId ?: "")
                .blockingGet()

            val location = downloadUrlResponse.location
            if (location.isNullOrEmpty()) {
                L.e { "[GroupAvatarUtil] get group avatar location is null" }
                throw NetworkException(message = "get group avatar location is null")
            }

            // 2. 下载文件内容
            val responseBody = EntryPointAccessors.fromApplication<EntryPoint>(context)
                .fileClient()
                .httpService
                .getResponseBody(location, emptyMap(), emptyMap())
                .blockingFirst()

            val bytes = responseBody.bytes()
            L.d { "[GroupAvatarUtil] fetchGroupAvatar success: ${bytes.size}" }

            // 3. 解密数据
            val decryptPass: ByteArray = Base64.decode(groupAvatarData.encryptionKey, Base64.DEFAULT)
            val digest: ByteArray = Base64.decode(groupAvatarData.digest, Base64.DEFAULT)
            val decData = decryptGroupAvatar(bytes, decryptPass, digest, groupAvatarData.byteCount?.toIntOrNull() ?: 0)

            L.d { "[GroupAvatarUtil] cbcDecrypt group avatar success: ${decData.size}" }
            return decData

        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] fetchGroupAvatar error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    // 智能群头像加载方法，合并了下载、解密、加载和保存
    suspend fun loadGroupAvatar(
        context: Context,
        groupAvatarData: GroupAvatarData,
        imageView: ImageView,
        size: AvatarCacheSize,
        forceRefresh: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val serverId = groupAvatarData.serverId ?: return@withContext
        try {
            // 1. 检查本地缓存
            val file = getGroupAvatarFile(serverId, size)
            if (!forceRefresh && file.exists()) {
                // 本地文件存在，直接加载
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.VISIBLE
                    Glide.with(context)
                        .asBitmap()
                        .load(file)
                        .into(imageView)
                }
                return@withContext
            }

            // 2. 本地文件不存在，从网络下载并解密
            val result = fetchGroupAvatar(context, groupAvatarData)

            // 3. 加载并保存
            loadAndSaveBitmap(context, result, serverId, imageView, size)
        } catch (e: Exception) {
            L.e { "[AvatarUtil] loadAvatar first attempt failed: ${e.message}" }
            // 重试一次
            try {
                val result = fetchGroupAvatar(context, groupAvatarData)
                loadAndSaveBitmap(context, result, serverId, imageView, size)
            } catch (retryException: Exception) {
                L.e { "[AvatarUtil] loadAvatar retry also failed: ${retryException.message}" }
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

        // 如果没有指定 unpaddedSize，则直接返回解密后的数据
        if (paddedSize == 0) {
            println("Decrypted attachment with unspecified size.")
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
     * 生成群头像文件（PNG），返回文件路径。
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
            e.printStackTrace()
            L.e { "[GroupAvatarUtil] generateAvatarFile error: ${e.stackTraceToString()}" }
            null
        }
    }
}