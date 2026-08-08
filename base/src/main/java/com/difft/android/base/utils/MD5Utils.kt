package com.difft.android.base.utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * MD5 hashing helpers. Hex output is lower-case.
 */
object MD5Utils {

    private val DIGESTER_CONTEXT = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest =
            try {
                MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
    }

    @JvmStatic
    fun md5AndHexStr(s: String): String = md5AndHex(s.toByteArray(StandardCharsets.UTF_8))

    @JvmStatic
    fun md5AndHex(data: ByteArray): String = md5AndHex(data, 0, data.size)

    @JvmStatic
    fun md5AndHex(data: ByteArray, start: Int, len: Int): String = byteToHex(md5(data, start, len))

    @JvmStatic
    fun md5(data: ByteArray, start: Int, len: Int): ByteArray {
        val digester = DIGESTER_CONTEXT.get()
        digester.update(data, start, len)
        return digester.digest()
    }

    @JvmStatic
    fun byteToHex(b: ByteArray): String {
        val sb = StringBuilder()
        for (t in b) {
            sb.append(String.format("%02x", t))
        }
        return sb.toString()
    }
}
