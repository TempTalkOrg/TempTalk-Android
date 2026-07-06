package com.difft.android.base.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.EncodeStrategy
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceEncoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.bitmap.BitmapEncoder
import com.difft.android.base.log.lumberjack.L
import java.io.BufferedOutputStream
import java.io.File

/**
 * Writes a decoded [Bitmap] into Glide's RESOURCE disk cache, compressing it and then encrypting the
 * bytes via [EncryptedCacheCoder]. The companion read path is [EncryptedCacheResourceDecoder].
 *
 * Mirrors Glide's own [BitmapEncoder] compression-format / quality selection, so behavior matches the
 * default encoder except the output stream is encrypted.
 */
class EncryptedBitmapResourceEncoder(
    private val coder: EncryptedCacheCoder
) : ResourceEncoder<Bitmap> {

    override fun getEncodeStrategy(options: Options): EncodeStrategy = EncodeStrategy.TRANSFORMED

    override fun encode(data: Resource<Bitmap>, file: File, options: Options): Boolean {
        val bitmap = data.get()
        val format = chooseFormat(bitmap, options)
        val quality = options.get(BitmapEncoder.COMPRESSION_QUALITY) ?: DEFAULT_QUALITY
        return try {
            BufferedOutputStream(coder.encryptedOutput(file)).use { out ->
                bitmap.compress(format, quality, out)
                out.flush()
            }
            L.d { "[EncCache] WRITE ${file.name} (${file.length()}B, ${bitmap.width}x${bitmap.height}, fmt=$format, q=$quality)" }
            true
        } catch (e: Throwable) {
            L.w(e) { "[EncCache] WRITE FAILED ${file.name}" }
            false
        }
    }

    private fun chooseFormat(bitmap: Bitmap, options: Options): Bitmap.CompressFormat {
        options.get(BitmapEncoder.COMPRESSION_FORMAT)?.let { return it }
        return if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    }

    companion object {
        private const val DEFAULT_QUALITY = 90
    }
}
