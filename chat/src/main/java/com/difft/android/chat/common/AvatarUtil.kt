package com.difft.android.chat.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx3.awaitFirst
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
        // 嘗試將最後一個字符轉換為數字
        val lastCharToInt = id.last().toString().toIntOrNull()

        // 如果最後一個字符是數字，則使用該數字；否則使用字符的 ASCII 碼
        val index = lastCharToInt ?: id.last().code

        // 進一步對結果進行取模，確保索引在 colors 數組範圍內
        val safeIndex = index % colors.size
        return colors[safeIndex]
    }

    /**
     * Get existing cache file, checking new format first, then legacy _SMALL format
     */
    fun getCacheFile(url: String): File? {
        val baseName = "avatar_${url.substringAfterLast("/")}"
        val cacheDir = FileUtil.getAvatarCachePath()

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
    private fun getNewCacheFile(url: String): File {
        val fileName = "avatar_${url.substringAfterLast("/")}"
        return File(FileUtil.getAvatarCachePath(), fileName)
    }


    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.NoHeader
        fun httpClient1(): ChativeHttpClient
    }

    suspend fun fetchAvatar(context: Context, url: String, key: String): ByteArray {
        try {
            return EntryPointAccessors.fromApplication<EntryPoint>(context)
                .httpClient1()
                .httpService
                .getResponseBody(url, emptyMap(), emptyMap())
                .map { responseBody ->
                    val bytes = responseBody.bytes()
                    val secretKey = SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AESGCM256")
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val params = GCMParameterSpec(128, bytes, 0, 12)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, params)
                    val decData = cipher.doFinal(bytes, 12, bytes.size - 12)
                    decData
                }
                .awaitFirst()
        } catch (e: Exception) {
            L.e(e) { "[AvatarUtil] fetchAvatar error:" }
            throw e
        }
    }

    /**
     * Ensure avatar is cached. Downloads and decrypts if not already cached.
     * This method is safe to call from any scope - it only handles caching, not UI.
     * @return The cache file if successful, null if failed
     */
    suspend fun ensureCached(context: Context, url: String, key: String): File? = withContext(Dispatchers.IO) {
        try {
            // Check if already cached
            val existingCache = getCacheFile(url)
            if (existingCache != null) {
                return@withContext existingCache
            }

            // Download and decrypt
            val bytes = fetchAvatar(context, url, key)
            
            // Save to cache
            val cacheFile = getNewCacheFile(url)
            cacheFile.writeBytes(bytes)
            return@withContext cacheFile
        } catch (e: Exception) {
            L.e { "[AvatarUtil] ensureCached failed: ${e.message}" }
            // Retry once
            try {
                val bytes = fetchAvatar(context, url, key)
                val cacheFile = getNewCacheFile(url)
                cacheFile.writeBytes(bytes)
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