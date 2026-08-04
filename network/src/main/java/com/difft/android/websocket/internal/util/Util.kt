package com.difft.android.websocket.internal.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import java.util.Collections

object Util {

    @JvmStatic
    fun join(vararg input: ByteArray): ByteArray {
        return try {
            val baos = ByteArrayOutputStream()
            for (part in input) {
                baos.write(part)
            }
            baos.toByteArray()
        } catch (e: IOException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun join(list: Collection<String>, delimiter: String): String {
        val result = StringBuilder()
        var i = 0
        for (item in list) {
            result.append(item)
            if (++i < list.size) {
                result.append(delimiter)
            }
        }
        return result.toString()
    }

    @JvmStatic
    fun split(input: ByteArray, firstLength: Int, secondLength: Int): Array<ByteArray> {
        val first = ByteArray(firstLength)
        System.arraycopy(input, 0, first, 0, firstLength)

        val second = ByteArray(secondLength)
        System.arraycopy(input, firstLength, second, 0, secondLength)

        return arrayOf(first, second)
    }

    @JvmStatic
    fun trim(input: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        System.arraycopy(input, 0, result, 0, result.size)
        return result
    }

    @JvmStatic
    fun isEmpty(value: String?): Boolean {
        // Matches Java String.trim() semantics (strips chars <= 0x20), not Kotlin's Unicode-whitespace trim.
        return value == null || value.trim { it <= ' ' }.isEmpty()
    }

    @JvmStatic
    fun getSecretBytes(size: Int): ByteArray {
        val secret = ByteArray(size)
        SecureRandom().nextBytes(secret)
        return secret
    }

    @JvmStatic
    fun getRandomLengthBytes(maxSize: Int): ByteArray {
        val secureRandom = SecureRandom()
        val result = ByteArray(secureRandom.nextInt(maxSize) + 1)
        secureRandom.nextBytes(result)
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFullyAsBytes(input: InputStream): ByteArray {
        val bout = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            bout.write(buffer, 0, read)
        }

        input.close()

        return bout.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream): String {
        return String(readFullyAsBytes(input))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0

        while (true) {
            val read = input.read(buffer, offset, buffer.size - offset)

            if (read + offset < buffer.size) offset += read
            else return
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(4096)
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }

        input.close()
        out.close()
    }

    @JvmStatic
    fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun wait(lock: Any, millis: Long) {
        try {
            (lock as java.lang.Object).wait(millis)
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun toIntExact(value: Long): Int {
        if (value.toInt().toLong() != value) {
            throw ArithmeticException("integer overflow")
        }
        return value.toInt()
    }

    @JvmStatic
    fun <T> immutableList(vararg elements: T): List<T> {
        return Collections.unmodifiableList(Arrays.asList(*elements.clone()))
    }

    @JvmStatic
    fun parseInt(integer: String?, defaultValue: Int): Int {
        return try {
            Integer.parseInt(integer)
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    @JvmStatic
    fun parseLong(longString: String?, defaultValue: Long): Long {
        return try {
            java.lang.Long.parseLong(longString)
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }
}
