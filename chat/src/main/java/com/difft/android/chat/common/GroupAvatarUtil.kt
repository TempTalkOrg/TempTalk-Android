package com.difft.android.chat.common


import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.application
import com.difft.android.base.utils.getSafeContext
import com.difft.android.chat.media.AvatarEncryptedProvider
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

    private fun saveToCache(attachmentId: String, bytes: ByteArray) {
        try {
            
            AvatarCacheCipher.writeEncrypted(getNewCacheFile(attachmentId), bytes)
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] saveToCache error: ${e.message}" }
        }
    }

    


    fun getCacheFile(attachmentId: String): File? {
        val baseName = "avatar_$attachmentId"
        val cacheDir = FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR)

        
        val newFile = File(cacheDir, baseName)
        if (newFile.exists()) return newFile

        
        val legacyFile = File(cacheDir, "${baseName}_SMALL")
        if (legacyFile.exists()) return legacyFile

        return null
    }

    


    private fun getNewCacheFile(attachmentId: String): File {
        val fileName = "avatar_$attachmentId"
        return File(FileUtil.getFilePath(FileUtil.FILE_DIR_GROUP_AVATAR), fileName)
    }

    



    fun cacheContentUri(attachmentId: String): android.net.Uri? {
        val file = getCacheFile(attachmentId) ?: return null
        return AvatarEncryptedProvider.contentUri(AvatarEncryptedProvider.DIR_GROUP_AVATAR, file.name)
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun chatHttpClient(): ChativeHttpClient

        @ChativeHttpClientModule.NoHeader
        fun fileClient(): ChativeHttpClient
    }

    


    suspend fun fetchGroupAvatar(context: Context, groupAvatarData: GroupAvatarData): ByteArray {
        try {
            
            val downloadUrlResponse = EntryPointAccessors.fromApplication<EntryPoint>(context)
                .chatHttpClient()
                .httpService
                .getDownloadUrl(groupAvatarData.serverId ?: "")

            val location = downloadUrlResponse.location
            if (location.isNullOrEmpty()) {
                L.e { "[GroupAvatarUtil] get group avatar location is null" }
                throw NetworkException(message = "get group avatar location is null")
            }

            
            val bytes = withContext(Dispatchers.IO) {
                EntryPointAccessors.fromApplication<EntryPoint>(context)
                    .fileClient()
                    .httpService
                    .getResponseBodyStreaming(location, emptyMap(), emptyMap())
                    .readBoundedBytes()
            }

            
            val decryptPass: ByteArray = Base64.decode(groupAvatarData.encryptionKey, Base64.DEFAULT)
            val digest: ByteArray = Base64.decode(groupAvatarData.digest, Base64.DEFAULT)
            val decData = decryptGroupAvatar(bytes, decryptPass, digest, groupAvatarData.byteCount?.toIntOrNull() ?: 0)
            return decData

        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] fetchGroupAvatar error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    




    suspend fun ensureCached(context: Context, groupAvatarData: GroupAvatarData): File? = withContext(Dispatchers.IO) {
        val serverId = groupAvatarData.serverId ?: return@withContext null
        try {
            
            val existingCache = getCacheFile(serverId)
            if (existingCache != null) {
                return@withContext existingCache
            }

            
            val bytes = fetchGroupAvatar(context, groupAvatarData)
            
            
            val cacheFile = getNewCacheFile(serverId)
            AvatarCacheCipher.writeEncrypted(cacheFile, bytes)
            return@withContext cacheFile
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] ensureCached failed: ${e.message}" }
            
            try {
                val bytes = fetchGroupAvatar(context, groupAvatarData)
                val cacheFile = getNewCacheFile(serverId)
                AvatarCacheCipher.writeEncrypted(cacheFile, bytes)
                return@withContext cacheFile
            } catch (retryException: Exception) {
                L.e { "[GroupAvatarUtil] ensureCached retry also failed: ${retryException.message}" }
                return@withContext null
            }
        }
    }

    



    suspend fun loadGroupAvatar(
        context: Context,
        groupAvatarData: GroupAvatarData,
        imageView: ImageView,
        forceRefresh: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val serverId = groupAvatarData.serverId ?: return@withContext false
        try {
            
            val cacheFile = getCacheFile(serverId)
            if (!forceRefresh && cacheFile != null) {
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.VISIBLE
                    loadEncryptedInto(context, imageView, cacheFile)
                }
                return@withContext true
            }

            
            val bytes = fetchGroupAvatar(context, groupAvatarData)

            
            saveToCache(serverId, bytes)

            
            val newCacheFile = getNewCacheFile(serverId)
            withContext(Dispatchers.Main) {
                imageView.visibility = android.view.View.VISIBLE
                loadEncryptedInto(context, imageView, newCacheFile)
            }
            return@withContext true
        } catch (e: Exception) {
            L.e { "[GroupAvatarUtil] loadGroupAvatar first attempt failed: ${e.message}" }
            
            try {
                val bytes = fetchGroupAvatar(context, groupAvatarData)
                saveToCache(serverId, bytes)
                val newCacheFile = getNewCacheFile(serverId)
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.VISIBLE
                    loadEncryptedInto(context, imageView, newCacheFile)
                }
                return@withContext true
            } catch (retryException: Exception) {
                L.e { "[GroupAvatarUtil] loadGroupAvatar retry also failed: ${retryException.message}" }
                return@withContext false
            }
        }
    }


    





    private fun loadEncryptedInto(context: Context, imageView: ImageView, cacheFile: File) {
        val uri = AvatarEncryptedProvider.contentUri(AvatarEncryptedProvider.DIR_GROUP_AVATAR, cacheFile.name)
        Glide.with(context.getSafeContext())
            .load(uri)
            .signature(ObjectKey(cacheFile.lastModified()))
            .diskCacheStrategy(
                if (GlideCacheKeyManager.isCacheKeyReady(application)) DiskCacheStrategy.RESOURCE
                else DiskCacheStrategy.NONE
            )
            .into(imageView)
    }

    private fun decryptGroupAvatar(
        dataToDecrypt: ByteArray,
        key: ByteArray,
        digest: ByteArray?,
        paddedSize: Int
    ): ByteArray {
        if (digest == null || digest.isEmpty()) {
            throw IllegalArgumentException("Digest is required for decryption.")
        }

        val ivLength = 16 
        val macLength = 32 
        if (dataToDecrypt.size < ivLength + macLength) {
            throw IllegalArgumentException("Message shorter than crypto overhead!")
        }

        val aesKey = key.copyOfRange(0, 32) 
        val hmacKey = key.copyOfRange(32, 64) 

        val iv = dataToDecrypt.copyOfRange(0, ivLength)
        val encryptedData = dataToDecrypt.copyOfRange(ivLength, dataToDecrypt.size - macLength)
        val providedHmac = dataToDecrypt.copyOfRange(dataToDecrypt.size - macLength, dataToDecrypt.size)

        val mac = Mac.getInstance("HmacSHA256")
        val macKeySpec = SecretKeySpec(hmacKey, "HmacSHA256")
        mac.init(macKeySpec)
        val calculatedHmac = mac.doFinal(dataToDecrypt.copyOfRange(0, ivLength + encryptedData.size))

        if (!MessageDigest.isEqual(providedHmac, calculatedHmac)) {
            throw SecurityException("MAC validation failed. Data integrity is compromised.")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val paddedPlainText = cipher.doFinal(encryptedData)

        
        if (paddedSize == 0) {
            return paddedPlainText
        }

        if (paddedSize > paddedPlainText.size) {
            throw IllegalArgumentException("Unpadded size is greater than padded data length.")
        }

        return if (paddedSize == paddedPlainText.size) {
            paddedPlainText
        } else {
            paddedPlainText.copyOfRange(0, paddedSize)
        }
    }

    fun encryptGroupAvatar(
        dataToEncrypt: ByteArray,
        keySize: Int = 64 
    ): Map<String, Any> {
        val key = ByteArray(keySize)
        SecureRandom().nextBytes(key)
        val aesKey = key.copyOfRange(0, 32) 
        val hmacKey = key.copyOfRange(32, 64) 

        val iv = ByteArray(16) 
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(dataToEncrypt)

        val encryptedPayload = iv + encryptedData

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val hmac = mac.doFinal(encryptedPayload)

        
        val finalEncryptedData = encryptedPayload + hmac

        val digest = MessageDigest.getInstance("SHA-256").digest(finalEncryptedData)
        val digestBase64 = Base64.encodeToString(digest, Base64.NO_WRAP)

        val encryptionKeyBase64 = Base64.encodeToString(key, Base64.NO_WRAP)

        return mapOf(
            "encryptionKey" to encryptionKeyBase64,
            "digest" to digestBase64,
            "encryptedData" to finalEncryptedData
        )
    }

    


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
