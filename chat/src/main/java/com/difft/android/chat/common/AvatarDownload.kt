package com.difft.android.chat.common

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException






internal const val AVATAR_DOWNLOAD_MAX_BYTES: Long = 10L * 1024 * 1024








@Throws(IOException::class)
internal fun ResponseBody.readBoundedBytes(maxBytes: Long = AVATAR_DOWNLOAD_MAX_BYTES): ByteArray = use { body ->
    val declared = body.contentLength()
    if (declared >= 0 && declared > maxBytes) {
        throw IOException("Avatar download exceeds max size: contentLength=$declared max=$maxBytes")
    }
    val initialCapacity = if (declared in 0..maxBytes) declared.toInt() else DEFAULT_BUFFER_SIZE
    val out = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    val input = body.byteStream()
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            throw IOException("Avatar download exceeds max size: read>$maxBytes max=$maxBytes")
        }
        out.write(buffer, 0, read)
    }
    out.toByteArray()
}
