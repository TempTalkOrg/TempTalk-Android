package com.difft.android.base.utils

import java.io.IOException
import java.util.Base64 as JdkBase64

/**
 * Standard RFC-4648 Base64 over java.util.Base64 — 1:1 replacement of the vendored iHarder
 * impl (#1093). Exposes only the 6 live members, preserving the IOException throw-contract that
 * callers depend on (Data.kt:70, PipeDecryptTool.kt:30-34, JsonUtil.kt:78-95). minSdk=26 guarantees
 * java.util.Base64; delegating to the JDK (not android.util.Base64) keeps it JVM/Robolectric-testable.
 */
object Base64 {

    const val NO_OPTIONS = 0

    @JvmStatic
    fun encodeBytes(source: ByteArray): String =
        JdkBase64.getEncoder().encodeToString(source)                 // standard alphabet, no line breaks; never throws

    @JvmStatic
    @Throws(IOException::class)                                        // preserves Java-visible signature; body never throws (options == NO_OPTIONS, no GZIP)
    fun encodeBytes(source: ByteArray?, options: Int): String =       // nullable to match the vendored Java platform-type contract (NPE-on-null preserved by JDK)
        JdkBase64.getEncoder().encodeToString(source)

    @JvmStatic
    fun encodeBytesWithoutPadding(source: ByteArray): String =
        JdkBase64.getEncoder().withoutPadding().encodeToString(source) // strips trailing '='

    @JvmStatic
    @Throws(IOException::class)
    fun decode(s: String?): ByteArray =                               // nullable to match the vendored Java platform-type contract (NPE-on-null preserved by JDK)
        try {
            // Strip only whitespace (which iHarder tolerated), then decode STRICTLY: any other
            // non-alphabet character must throw, matching iHarder's "Bad Base64 input character".
            // getMimeDecoder() would silently skip ALL non-alphabet bytes and accept corrupt input.
            JdkBase64.getDecoder().decode(s!!.replace(WHITESPACE, ""))
        } catch (e: IllegalArgumentException) {
            throw IOException(e)                                       // the one hard incompatibility: IAE -> IOException
        }

    private val WHITESPACE = Regex("[ \t\r\n]")

    @JvmStatic
    @Throws(IOException::class)
    fun decodeWithoutPadding(source: String): ByteArray {
        val padded = when (source.length % 4) {                       // re-pad, then decode (matches iHarder)
            2 -> "$source=="
            3 -> "$source="
            else -> source
        }
        return decode(padded)
    }
}
