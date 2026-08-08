/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package util

import com.difft.android.base.log.lumberjack.L
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Utility methods for input and output streams.
 */
object StreamUtil {

    private const val TAG = "StreamUtil"

    @JvmStatic
    fun close(closeable: Closeable?) {
        if (closeable == null) return

        try {
            closeable.close()
        } catch (e: IOException) {
            L.w(e) { TAG }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getStreamLength(input: InputStream): Long {
        val buffer = ByteArray(4096)
        var totalSize = 0

        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            totalSize += read
        }

        return totalSize.toLong()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray) {
        readFully(input, buffer, buffer.size)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray, len: Int) {
        var offset = 0

        while (true) {
            val read = input.read(buffer, offset, len - offset)
            if (read == -1) throw EOFException("Stream ended early, offset: $offset len: $len")

            if (read + offset < len) offset += read
            else return
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream): ByteArray = readFully(input, Int.MAX_VALUE)

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream, maxBytes: Int): ByteArray = readFully(input, maxBytes, true)

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream, maxBytes: Int, closeWhenDone: Boolean): ByteArray {
        val bout = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalRead = 0
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            bout.write(buffer, 0, read)
            totalRead += read
            if (totalRead > maxBytes) {
                throw IOException("Stream size limit exceeded")
            }
        }

        if (closeWhenDone) {
            input.close()
        }

        return bout.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFullyAsString(input: InputStream): String = String(readFully(input))

    @JvmStatic
    @Throws(IOException::class)
    fun copy(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var read: Int
        var total = 0L

        while (input.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
            total += read
        }

        input.close()
        out.close()

        return total
    }
}
