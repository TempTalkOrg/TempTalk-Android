package com.difft.android.chat.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.Base64
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
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
        // 嘗試將最後一個字符轉換為數字
        val lastCharToInt = id.last().toString().toIntOrNull()

        // 如果最後一個字符是數字，則使用該數字；否則使用字符的 ASCII 碼
        val index = lastCharToInt ?: id.last().code

        // 進一步對結果進行取模，確保索引在 colors 數組範圍內
        val safeIndex = index % colors.size
        return colors[safeIndex]
    }

    fun saveBitmapFile(url: String, bitmap: Bitmap, size: AvatarCacheSize) {
        try {
            val file = getFileFormUrl(url, size)
            BufferedOutputStream(FileOutputStream(file)).use { bos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos)
                bos.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    enum class AvatarCacheSize {
        BIG, SMALL
    }

    fun getFileFormUrl(url: String, size: AvatarCacheSize): File {
        val fileName = "avatar_${url.substringAfterLast("/")}_$size"
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
                .blockingFirst() // 使用 blockingFirst() 替代 suspendCancellableCoroutine
        } catch (e: Exception) {
            L.e { "[AvatarUtil] fetchAvatar error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    // 加载图片并保存到本地
    suspend fun loadAndSaveBitmap(context: Context, content: Any, url: String, imageView: ImageView, defaultDisplayView: View, size: AvatarCacheSize) {
        // 获取 ImageView 尺寸，如果没有则使用默认值
        val targetWidth = if (imageView.width > 0) imageView.width else {
            when (size) {
                AvatarCacheSize.SMALL -> 50.dp // 50dp
                AvatarCacheSize.BIG -> -1 // 保持原尺寸
            }
        }

        val targetHeight = if (imageView.height > 0) imageView.height else {
            when (size) {
                AvatarCacheSize.SMALL -> 50.dp // 50dp
                AvatarCacheSize.BIG -> -1 // 保持原尺寸
            }
        }
        L.d { "[AvatarUtil] loadAndSaveBitmap targetWidth:${targetWidth} targetHeight:${targetHeight}" }
        // 直接获取调整后的 Bitmap（已经在 IO 线程中，不需要 withContext）
        val bitmap = Glide.with(context)
            .asBitmap()
            .load(content)
            .submit(targetWidth, targetHeight) // 使用计算后的尺寸
            .get()

        // 保存到本地
        saveBitmapFile(url, bitmap, size)

        // 设置到 ImageView
        withContext(Dispatchers.Main) {
            imageView.visibility = View.VISIBLE
            defaultDisplayView.visibility = View.GONE
            imageView.setImageBitmap(bitmap)
        }
    }

    // 智能头像加载方法，合并了下载、解密、加载和保存
    suspend fun loadAvatar(
        context: Context,
        url: String,
        key: String,
        imageView: ImageView,
        defaultDisplayView: View,
        size: AvatarCacheSize,
        forceRefresh: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. 检查本地缓存
            val file = getFileFormUrl(url, size)
            if (!forceRefresh && file.exists()) {
                // 本地文件存在，直接加载
                withContext(Dispatchers.Main) {
                    defaultDisplayView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    Glide.with(context)
                        .asBitmap()
                        .load(file)
                        .into(imageView)
                }
                return@withContext
            }

            // 2. 本地文件不存在，从网络下载并解密
            val result = fetchAvatar(context, url, key)

            // 3. 加载并保存
            loadAndSaveBitmap(context, result, url, imageView, defaultDisplayView, size)
        } catch (e: Exception) {
            L.e { "[AvatarUtil] loadAvatar first attempt failed: ${e.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                imageView.visibility = View.INVISIBLE
                defaultDisplayView.visibility = View.VISIBLE
            }
            // 重试一次
            try {
                val result = fetchAvatar(context, url, key)
                loadAndSaveBitmap(context, result, url, imageView, defaultDisplayView, size)
            } catch (retryException: Exception) {
                L.e { "[AvatarUtil] loadAvatar retry also failed: ${retryException.message}" }
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
            e.printStackTrace()
            L.e { "[AvatarUtil] generateRandomAvatarFile error:" + e.stackTraceToString() }
        }
        return filePath
    }
}