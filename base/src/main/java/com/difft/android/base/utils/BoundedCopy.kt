package com.difft.android.base.utils

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


class SizeLimitExceededException(val maxBytes: Long) : IOException("stream exceeds $maxBytes bytes")





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
