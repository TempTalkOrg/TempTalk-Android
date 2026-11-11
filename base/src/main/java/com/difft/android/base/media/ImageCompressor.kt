package com.difft.android.base.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.WorkerThread
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

object ImageCompressor {

    sealed interface Result {
        
        data class Compressed(
            val file: File,
            val mime: String,
            val width: Int,
            val height: Int,
            val size: Long,
            val tier: Int
        ) : Result

        data class Failed(val reason: FailureReason) : Result
    }

    enum class FailureReason { INVALID_ARGUMENT, DECODE, ENCODE, IO, OOM }

    @WorkerThread
    fun compress(
        context: Context,
        source: Uri,
        sourceMime: String?,
        targets: IntArray,
        maxBytes: Int,
        quality: Int,
        outputDir: File
    ): Result {
        var reason = FailureReason.DECODE
        var pendingOutput: File? = null
        return try {
            val dimensions = targets.copyOf()
            if (dimensions.isEmpty() || dimensions.any { it <= 0 } || maxBytes <= 0 ||
                quality !in 0..100 || dimensions.indices.any { it > 0 && dimensions[it] > dimensions[it - 1] }
            ) {
                return Result.Failed(FailureReason.INVALID_ARGUMENT)
            }
            val appContext = context.applicationContext
            val requestManager = Glide.with(appContext)
            val future = requestManager.asBitmap()
                .load(source)
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .disallowHardwareConfig()
                .centerInside()
                .submit(dimensions[0], dimensions[0])
            val result = try {
                val bitmap = future.get()
                reason = FailureReason.ENCODE
                val format = outputFormat(sourceMime, bitmap)
                val encoded = encodeTiers(bitmap, format, dimensions, maxBytes, quality)
                reason = FailureReason.IO
                if (!outputDir.isDirectory && !outputDir.mkdirs() && !outputDir.isDirectory) {
                    throw IOException("Cannot create compression output directory")
                }
                val file = File(outputDir, "${UUID.randomUUID()}.${format.extension}")
                pendingOutput = file
                file.outputStream().use { it.write(encoded.bytes) }
                Result.Compressed(file, format.mime, encoded.width, encoded.height, file.length(), encoded.tier)
            } finally {
                
                requestManager.clear(future)
            }
            pendingOutput = null
            result
        } catch (_: OutOfMemoryError) {
            Result.Failed(FailureReason.OOM)
        } catch (_: Exception) {
            Result.Failed(reason)
        } finally {
            
            pendingOutput?.let { file -> runCatching { file.delete() } }
        }
    }

    private data class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val tier: Int)

    private fun encodeTiers(
        original: Bitmap,
        format: OutputFormat,
        targets: IntArray,
        maxBytes: Int,
        quality: Int
    ): Encoded {
        targets.forEachIndexed { tier, target ->
            val scale = minOf(1.0, target.toDouble() / maxOf(original.width, original.height))
            val width = (original.width * scale).roundToInt().coerceAtLeast(1)
            val height = (original.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = if (width == original.width && height == original.height) original else {
                Bitmap.createScaledBitmap(original, width, height, true)
            }
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(format.encoding, quality, output)) { "Bitmap encoding failed" }
                    output.toByteArray()
                }
                if (bytes.size <= maxBytes || tier == targets.lastIndex) {
                    return Encoded(bytes, bitmap.width, bitmap.height, tier)
                }
            } finally {
                
                if (bitmap !== original) bitmap.recycle()
            }
        }
        error("No dimension targets")
    }

    internal enum class OutputFormat(val mime: String, val extension: String, val encoding: Bitmap.CompressFormat) {
        JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
        PNG("image/png", "png", Bitmap.CompressFormat.PNG),
        WEBP("image/webp", "webp", webpEncoding())
    }

    internal fun outputFormat(sourceMime: String?, bitmap: Bitmap): OutputFormat =
        when (sourceMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg", "image/heic", "image/heif", "image/avif" -> OutputFormat.JPEG
            "image/webp" -> OutputFormat.WEBP
            "image/png" -> if (hasTransparentPixels(bitmap)) OutputFormat.PNG else OutputFormat.JPEG
            else -> OutputFormat.PNG
        }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 < 255 }) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun webpEncoding(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}
