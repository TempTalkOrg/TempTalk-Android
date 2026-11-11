package com.difft.android.base.utils

import java.io.IOException
import java.util.Base64 as JdkBase64







object Base64 {

    const val NO_OPTIONS = 0

    @JvmStatic
    fun encodeBytes(source: ByteArray): String =
        JdkBase64.getEncoder().encodeToString(source)                 

    @JvmStatic
    @Throws(IOException::class)                                        
    fun encodeBytes(source: ByteArray?, options: Int): String =       
        JdkBase64.getEncoder().encodeToString(source)

    @JvmStatic
    fun encodeBytesWithoutPadding(source: ByteArray): String =
        JdkBase64.getEncoder().withoutPadding().encodeToString(source) 

    @JvmStatic
    @Throws(IOException::class)
    fun decode(s: String?): ByteArray =                               
        try {
            
            
            
            JdkBase64.getDecoder().decode(s!!.replace(WHITESPACE, ""))
        } catch (e: IllegalArgumentException) {
            throw IOException(e)                                       
        }

    private val WHITESPACE = Regex("[ \t\r\n]")

    @JvmStatic
    @Throws(IOException::class)
    fun decodeWithoutPadding(source: String): ByteArray {
        val padded = when (source.length % 4) {                       
            2 -> "$source=="
            3 -> "$source="
            else -> source
        }
        return decode(padded)
    }
}
