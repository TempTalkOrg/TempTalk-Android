package com.difft.android.chat.common

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Hard failsafe against an oversized (compromised/misbehaving server) avatar body OOMing the
 * client. Mirrors Signal's `AvatarHelper.AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE`; legitimate avatars
 * are client-side crop + compressed to well under this.
 */
internal const val AVATAR_DOWNLOAD_MAX_BYTES: Long = 10L * 1024 * 1024

/**
 * Reads a `@Streaming` response body into memory, capped at [maxBytes] — enforced on the actual
 * stream read, not just `Content-Length` (chunked responses may omit it). Call on `Dispatchers.IO`:
 * with `@Streaming` the network read happens here.
 *
 * @throws IOException when the body exceeds [maxBytes].
 */
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
