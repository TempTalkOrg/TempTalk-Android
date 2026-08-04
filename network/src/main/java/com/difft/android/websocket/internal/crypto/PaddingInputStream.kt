package com.difft.android.websocket.internal.crypto

import com.difft.android.websocket.internal.util.Util
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class PaddingInputStream(inputStream: InputStream, plaintextLength: Long) : FilterInputStream(inputStream) {

    private var paddingRemaining: Long = getPaddedSize(plaintextLength) - plaintextLength

    @Throws(IOException::class)
    override fun read(): Int {
        val result = super.read()
        if (result != -1) return result

        if (paddingRemaining > 0) {
            paddingRemaining--
            return 0x00
        }

        return -1
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var length = length
        val result = super.read(buffer, offset, length)
        if (result != -1) return result

        if (paddingRemaining > 0) {
            length = Math.min(length, Util.toIntExact(paddingRemaining))
            paddingRemaining -= length.toLong()
            return length
        }

        return -1
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return read(buffer, 0, buffer.size)
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return super.available() + Util.toIntExact(paddingRemaining)
    }

    companion object {
        @JvmStatic
        fun getPaddedSize(size: Long): Long {
            return Math.max(541.0, Math.floor(Math.pow(1.05, Math.ceil(Math.log(size.toDouble()) / Math.log(1.05))))).toInt().toLong()
        }

        @JvmStatic
        fun getMaxUnpaddedSize(maxPaddedSize: Long): Long {
            return Math.floor(Math.pow(1.05, Math.floor(Math.log(maxPaddedSize.toDouble()) / Math.log(1.05)))).toInt().toLong()
        }
    }
}
