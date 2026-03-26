package com.difft.android.chat.widget

import android.media.MediaDataSource

class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val toRead = minOf(size, (data.size - position).toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, toRead)
        return toRead
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
