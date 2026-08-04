package com.difft.android.websocket.api.util

import com.google.protobuf.ByteString
import java.util.Arrays
import java.util.Optional

object OptionalUtil {

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <E> or(vararg optionals: Optional<E>): Optional<E> {
        for (optional in optionals) {
            if (optional.isPresent) {
                return optional
            }
        }
        return Optional.empty<Any>() as Optional<E>
    }

    @JvmStatic
    fun byteArrayEquals(a: Optional<ByteArray>, b: Optional<ByteArray>): Boolean {
        return if (a.isPresent != b.isPresent) {
            false
        } else if (a.isPresent) {
            Arrays.equals(a.get(), b.get())
        } else {
            true
        }
    }

    @JvmStatic
    fun byteArrayHashCode(bytes: Optional<ByteArray>): Int {
        return if (bytes.isPresent) {
            Arrays.hashCode(bytes.get())
        } else {
            0
        }
    }

    @JvmStatic
    fun absentIfEmpty(value: String?): Optional<String> {
        return if (value == null || value.isEmpty()) {
            Optional.empty()
        } else {
            Optional.of(value)
        }
    }

    @JvmStatic
    fun absentIfEmpty(value: ByteString?): Optional<ByteArray> {
        return if (value == null || value.isEmpty) {
            Optional.empty()
        } else {
            Optional.of(value.toByteArray())
        }
    }
}
