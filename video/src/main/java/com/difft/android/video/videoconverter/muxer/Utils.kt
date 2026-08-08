package com.difft.android.video.videoconverter.muxer

import java.nio.ByteBuffer

/**
 * Based on https://github.com/jcodec/jcodec/blob/master/src/main/java/org/jcodec/codecs/h264/H264Utils.java
 */
object Utils {

    @JvmStatic
    fun toArray(buf: ByteBuffer): ByteArray {
        val newBuf = buf.duplicate()
        val bytes = ByteArray(newBuf.remaining())
        newBuf.get(bytes, 0, bytes.size)
        return bytes
    }

    @JvmStatic
    fun clone(original: ByteBuffer): ByteBuffer {
        val clone = ByteBuffer.allocate(original.capacity())
        original.rewind()
        clone.put(original)
        original.rewind()
        clone.flip()
        return clone
    }

    @JvmStatic
    fun subBuffer(buf: ByteBuffer, start: Int): ByteBuffer {
        return subBuffer(buf, start, buf.limit() - start)
    }

    @JvmStatic
    fun subBuffer(buf: ByteBuffer, start: Int, count: Int): ByteBuffer {
        val newBuf = buf.duplicate()
        val bytes = ByteArray(count)
        newBuf.position(start)
        newBuf.get(bytes, 0, bytes.size)
        return ByteBuffer.wrap(bytes)
    }
}
