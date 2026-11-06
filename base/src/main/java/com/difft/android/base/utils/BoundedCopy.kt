package com.difft.android.base.utils

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The stream held more than [maxBytes] bytes; whatever was written so far is incomplete. */
class SizeLimitExceededException(val maxBytes: Long) : IOException("stream exceeds $maxBytes bytes")

/**
 * Copies this stream to [out] and returns the byte count, failing as soon as it holds more than
 * [maxBytes]. For streams from other apps, whose reported size cannot be trusted (#1429).
 */
fun InputStream.copyToBounded(out: OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        if (total + read > maxBytes) throw SizeLimitExceededException(maxBytes)
        out.write(buffer, 0, read)
        total += read
    }
}
