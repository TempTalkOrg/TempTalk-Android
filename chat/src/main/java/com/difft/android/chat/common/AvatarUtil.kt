package com.difft.android.chat.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AvatarUtil {

    val colors = arrayListOf(
        Color.rgb(235, 69, 58),
        Color.rgb(235, 159, 11),
        Color.rgb(234, 215, 9),
        Color.rgb(49, 189, 91),
        Color.rgb(120, 195, 235),
        Color.rgb(11, 132, 235),
        Color.rgb(94, 92, 210),
        Color.rgb(213, 127, 225),
        Color.rgb(114, 126, 115),
        Color.rgb(235, 79, 121)
    )

    fun getBgColorResId(id: String): Int {
        if (TextUtils.isEmpty(id)) {
            return colors[0]
        }
        val lastCharToInt = id.last().toString().toIntOrNull()

        val index = lastCharToInt ?: id.last().code

        val safeIndex = index % colors.size
        return colors[safeIndex]
    }

    


    fun getCacheFile(url: String): File? {
        val baseName = "avatar_${url.substringAfterLast("/")}"
        val cacheDir = FileUtil.getAvatarCachePath()

        
        val newFile = File(cacheDir, baseName)
        if (newFile.exists()) return newFile

        
        val legacyFile = File(cacheDir, "${baseName}_SMALL")
        if (legacyFile.exists()) return legacyFile

        return null
    }


    


    private fun getNewCacheFile(url: String): File {
        val fileName = "avatar_${url.substringAfterLast("/")}"
        return File(FileUtil.getAvatarCachePath(), fileName)
    }

    




    fun cacheContentUri(url: String): android.net.Uri? {
        val file = getCacheFile(url) ?: return null
        return AvatarEncryptedProvider.contentUri(AvatarEncryptedProvider.DIR_AVATAR, file.name)
    }


    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.NoHeader
        fun httpClient1(): ChativeHttpClient
    }

    suspend fun fetchAvatar(context: Context, url: String, key: String): ByteArray {
        try {
            val bytes = withContext(Dispatchers.IO) {
                EntryPointAccessors.fromApplication<EntryPoint>(context)
                    .httpClient1()
                    .httpService
                    .getResponseBodyStreaming(url, emptyMap(), emptyMap())
                    .readBoundedBytes()
            }
            val secretKey = SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AESGCM256")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val params = GCMParameterSpec(128, bytes, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, params)
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        } catch (e: Exception) {
            L.e(e) { "[AvatarUtil] fetchAvatar error:" }
            throw e
        }
    }

    




    suspend fun ensureCached(context: Context, url: String, key: String): File? = withContext(Dispatchers.IO) {
        try {
            
            val existingCache = getCacheFile(url)
            if (existingCache != null) {
                return@withContext existingCache
            }

            
            val bytes = fetchAvatar(context, url, key)
            
            
            val cacheFile = getNewCacheFile(url)
            AvatarCacheCipher.writeEncrypted(cacheFile, bytes)
            return@withContext cacheFile
        } catch (e: Exception) {
            L.e { "[AvatarUtil] ensureCached failed: ${e.message}" }
            
            try {
                val bytes = fetchAvatar(context, url, key)
                val cacheFile = getNewCacheFile(url)
                AvatarCacheCipher.writeEncrypted(cacheFile, bytes)
                return@withContext cacheFile
            } catch (retryException: Exception) {
                L.e { "[AvatarUtil] ensureCached retry also failed: ${retryException.message}" }
                return@withContext null
            }
        }
    }

    fun generateRandomAvatarFile(): String {
        val filePath = "${FileUtil.getFilePath(FileUtil.FILE_DIR_AVATAR)}${System.currentTimeMillis()}.PNG"
        try {
            val bitmap = RandomAvatarGenerator.generateRandomBitmap()
            val bos = BufferedOutputStream(FileOutputStream(filePath))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.flush()
            bos.close()
            return filePath
        } catch (e: Exception) {
            L.e(e) { "[AvatarUtil] generateRandomAvatarFile error:" }
        }
        return filePath
    }
}