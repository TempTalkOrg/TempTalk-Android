package com.difft.android.chat.util

/**
 * Just like [java.util.concurrent.TimeUnit], but for bytes.
 */
enum class ByteUnit {

    BYTES {
        override fun toBytes(d: Long): Long = d
        override fun toKilobytes(d: Long): Long = d / 1024
        override fun toMegabytes(d: Long): Long = toKilobytes(d) / 1024
        override fun toGigabytes(d: Long): Long = toMegabytes(d) / 1024
    },

    KILOBYTES {
        override fun toBytes(d: Long): Long = d * 1024
        override fun toKilobytes(d: Long): Long = d
        override fun toMegabytes(d: Long): Long = d / 1024
        override fun toGigabytes(d: Long): Long = toMegabytes(d) / 1024
    },

    MEGABYTES {
        override fun toBytes(d: Long): Long = toKilobytes(d) * 1024
        override fun toKilobytes(d: Long): Long = d * 1024
        override fun toMegabytes(d: Long): Long = d
        override fun toGigabytes(d: Long): Long = d / 1024
    },

    GIGABYTES {
        override fun toBytes(d: Long): Long = toKilobytes(d) * 1024
        override fun toKilobytes(d: Long): Long = toMegabytes(d) * 1024
        override fun toMegabytes(d: Long): Long = d * 1024
        override fun toGigabytes(d: Long): Long = d
    };

    abstract fun toBytes(d: Long): Long
    abstract fun toKilobytes(d: Long): Long
    abstract fun toMegabytes(d: Long): Long
    abstract fun toGigabytes(d: Long): Long
}
