package com.difft.android.base.media

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.bumptech.glide.integration.webp.WebpHeaderParser
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.InputStream

object AnimatedImageDetector {

    @WorkerThread
    fun isAnimated(context: Context, source: Uri, mimeType: String?): Boolean =
        detect(mimeType) { context.contentResolver.openInputStream(source) }

    @WorkerThread
    fun isAnimated(file: File, mimeType: String?): Boolean = detect(mimeType) { file.inputStream() }

    private fun detect(mimeType: String?, openStream: () -> InputStream?): Boolean {
        if (mimeType?.trim().equals("image/gif", ignoreCase = true)) return true
        return try {
            val header = ByteArray(WebpHeaderParser.MAX_WEBP_HEADER_SIZE)
            val read = openStream()?.use { input ->
                var offset = 0
                
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    if (count == 0) {
                        val next = input.read()
                        if (next < 0) break
                        header[offset++] = next.toByte()
                    } else {
                        offset += count
                    }
                }
                offset
            } ?: return false
            if (read == 0) return false
            val bytes = if (read == header.size) header else header.copyOf(read)
            WebpHeaderParser.isAnimatedWebpType(WebpHeaderParser.getType(bytes))
        } catch (e: Exception) {
            L.w { "[Media] animated detect failed mime=$mimeType error=${e.javaClass.simpleName}" }
            false
        }
    }
}
