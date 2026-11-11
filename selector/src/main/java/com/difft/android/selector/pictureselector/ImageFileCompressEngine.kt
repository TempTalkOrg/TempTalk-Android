package com.difft.android.selector.pictureselector

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.difft.android.base.concurrent.AppExecutors
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.media.ImageCompressionPreset
import com.difft.android.base.media.ImageCompressor
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.engine.CompressFileEngine
import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Locale

class ImageFileCompressEngine : CompressFileEngine {

    companion object {
        const val CACHE_DIR_NAME = "avatar_compress"
        private const val MIN_COMPRESS_BYTES = 100L * 1024
    }

    override fun onStartCompress(context: Context, source: ArrayList<Uri>, call: OnKeyValueResultCallbackListener) {
        val inputs = source.toList()
        if (inputs.isEmpty()) return
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        
        AppExecutors.IO.execute {
            val results = inputs.map { input ->
                val key = if (PictureMimeType.isContent(input.toString())) input.toString() else input.path
                try {
                    compress(appContext, input, key)
                } catch (_: OutOfMemoryError) {
                    failed(key, "oom")
                } catch (_: Exception) {
                    failed(key, "io")
                }
            }
            val compressed = results.count { it.state == State.COMPRESSED }
            val failed = results.count { it.state == State.FAILED }
            L.i { "[AvatarCompress] batch total=${results.size} compressed=$compressed skipped=${results.size - compressed - failed} failed=$failed" }
            
            main.post {
                results.forEach { call.onCallback(it.source, it.path) }
            }
        }
    }

    private enum class State { COMPRESSED, SKIPPED, FAILED }
    private data class Output(val source: String?, val path: String?, val state: State)

    private fun compress(context: Context, source: Uri, key: String?): Output {
        val mime = mimeType(context, source)
        if (PictureMimeType.isUrlHasGif(key.orEmpty()) || mime == "image/gif" ||
            (mime != null && !mime.startsWith("image/")) || source.scheme !in listOf(null, "file", "content")
        ) {
            return Output(key, key, State.SKIPPED)
        }
        val size = sourceSize(context, source)
        if (size != null && size < MIN_COMPRESS_BYTES) return Output(key, key, State.SKIPPED)

        val cache = context.externalCacheDir ?: return failed(key, "cache_unavailable")
        val directory = File(cache, CACHE_DIR_NAME)
        
        if (!directory.absolutePath.contains("Android/data/")) return failed(key, "cache_path")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create avatar compression directory")
        }
        val input = if (source.scheme == null && key != null) Uri.fromFile(File(key)) else source
        val preset = ImageCompressionPreset.STANDARD
        return when (val result = ImageCompressor.compress(
            context, input, mime, preset.targets, preset.maxBytes, preset.quality, directory
        )) {
            is ImageCompressor.Result.Compressed -> Output(key, result.file.absolutePath, State.COMPRESSED)
            is ImageCompressor.Result.Failed -> failed(key, result.reason.name.lowercase(Locale.ROOT))
        }
    }

    private fun mimeType(context: Context, source: Uri): String? {
        val provided = if (source.scheme == "content") {
            runCatching { context.contentResolver.getType(source) }.getOrNull()
        } else null
        return (provided?.takeIf { it.isNotBlank() } ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            source.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        ))?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    }

    private fun sourceSize(context: Context, source: Uri): Long? {
        if (source.scheme != "content") {
            return runCatching { File(source.path ?: return null).takeIf { it.isFile }?.length() }.getOrNull()
        }
        val resolver = context.contentResolver
        val length = runCatching {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length.takeIf { size -> size >= 0 } }
        }.getOrNull()
        if (length != null) return length
        return runCatching {
            resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index).takeIf { it >= 0 }
                } else null
            }
        }.getOrNull()
    }

    private fun failed(key: String?, reason: String): Output {
        L.w { "[AvatarCompress] fallback reason=$reason" }
        return Output(key, key, State.FAILED)
    }
}
