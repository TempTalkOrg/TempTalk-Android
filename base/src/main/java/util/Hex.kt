package util

import java.io.IOException

/**
 * Utility for generating hex dumps.
 */
object Hex {

    private const val HEX_DIGITS_START = 10
    private const val ASCII_TEXT_START = HEX_DIGITS_START + (16 * 2 + (16 / 2))

    private val EOL: String = System.getProperty("line.separator") ?: "\n"

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    @JvmStatic
    fun toString(bytes: ByteArray): String = toString(bytes, 0, bytes.size)

    @JvmStatic
    fun toString(bytes: ByteArray, offset: Int, length: Int): String {
        val buf = StringBuilder()
        for (i in 0 until length) {
            appendHexChar(buf, bytes[offset + i].toInt())
            buf.append(' ')
        }
        return buf.toString()
    }

    @JvmStatic
    fun toStringCondensed(bytes: ByteArray?): String {
        if (bytes == null) {
            return ""
        }
        val buf = StringBuilder()
        for (aByte in bytes) {
            appendHexChar(buf, aByte.toInt())
        }
        return buf.toString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fromStringCondensed(encoded: String): ByteArray {
        val data = encoded.toCharArray()
        val len = data.size

        if ((len and 0x01) != 0) {
            throw IOException("Odd number of characters.")
        }

        val out = ByteArray(len shr 1)

        // two characters form the hex value.
        var i = 0
        var j = 0
        while (j < len) {
            var f = Character.digit(data[j], 16) shl 4
            j++
            f = f or Character.digit(data[j], 16)
            j++
            out[i] = (f and 0xFF).toByte()
            i++
        }

        return out
    }

    @JvmStatic
    fun fromStringOrThrow(encoded: String): ByteArray {
        return try {
            fromStringCondensed(encoded)
        } catch (e: IOException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun dump(bytes: ByteArray): String = dump(bytes, 0, bytes.size)

    @JvmStatic
    fun dump(bytes: ByteArray, offset: Int, length: Int): String {
        val buf = StringBuilder()
        val lines = ((length - 1) / 16) + 1

        for (i in 0 until lines) {
            val lineOffset = (i * 16) + offset
            val lineLength = minOf(16, (length - (i * 16)))
            appendDumpLine(buf, i, bytes, lineOffset, lineLength)
            buf.append(EOL)
        }

        return buf.toString()
    }

    private fun appendDumpLine(buf: StringBuilder, line: Int, bytes: ByteArray, lineOffset: Int, lineLength: Int) {
        buf.append(HEX_DIGITS[(line shr 28) and 0xf])
        buf.append(HEX_DIGITS[(line shr 24) and 0xf])
        buf.append(HEX_DIGITS[(line shr 20) and 0xf])
        buf.append(HEX_DIGITS[(line shr 16) and 0xf])
        buf.append(HEX_DIGITS[(line shr 12) and 0xf])
        buf.append(HEX_DIGITS[(line shr 8) and 0xf])
        buf.append(HEX_DIGITS[(line shr 4) and 0xf])
        buf.append(HEX_DIGITS[line and 0xf])
        buf.append(": ")

        for (i in 0 until 16) {
            val idx = i + lineOffset
            if (i < lineLength) {
                appendHexChar(buf, bytes[idx].toInt())
            } else {
                buf.append("  ")
            }
            if ((i % 2) == 1) {
                buf.append(' ')
            }
        }

        var i = 0
        while (i < 16 && i < lineLength) {
            val idx = i + lineOffset
            val b = bytes[idx].toInt()
            if (b in 0x20..0x7e) {
                buf.append(b.toChar())
            } else {
                buf.append('.')
            }
            i++
        }
    }

    private fun appendHexChar(buf: StringBuilder, b: Int) {
        buf.append(HEX_DIGITS[(b shr 4) and 0xf])
        buf.append(HEX_DIGITS[b and 0xf])
    }
}
