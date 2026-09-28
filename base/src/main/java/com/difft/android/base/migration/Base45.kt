package com.difft.android.base.migration

/**
 * Base45 (RFC 9285): every 2 bytes become 3 characters from a 45-character alphabet that QR
 * alphanumeric mode encodes at 5.5 bits each, keeping a binary pairing payload small in a QR.
 *
 * Decoding is strict — a stray character, a length leaving one character over, a triple above
 * 0xFFFF or a final pair above 0xFF all mean "not a payload"; every platform rejects the same
 * inputs. The alphabet contains a space, so callers strip line breaks and nothing else.
 */
object Base45 {
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private const val BASE = 45
    private const val BASE_SQUARED = BASE * BASE
    private const val MAX_PAIR = 0xFFFF
    private const val MAX_SINGLE = 0xFF

    private val index = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size / 2 * 3 + 2)
        var i = 0
        while (i + 1 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            out.append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE % BASE]).append(ALPHABET[n / BASE_SQUARED])
            i += 2
        }
        if (i < bytes.size) {
            val n = bytes[i].toInt() and 0xFF
            out.append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE])
        }
        return out.toString()
    }

    /** True when every character is in the alphabet and there is at least one. Shape only; [decode] still validates. */
    fun isAlphabet(text: String): Boolean = text.isNotEmpty() && text.all { it.code < index.size && index[it.code] >= 0 }

    /** The bytes, or null when [text] is not legal Base45. */
    fun decode(text: String): ByteArray? {
        if (!isAlphabet(text) || text.length % 3 == 1) return null
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i + 2 < text.length) {
            val n = index[text[i].code] + index[text[i + 1].code] * BASE + index[text[i + 2].code] * BASE_SQUARED
            if (n > MAX_PAIR) return null
            out[o++] = (n shr 8).toByte()
            out[o++] = (n and 0xFF).toByte()
            i += 3
        }
        if (i < text.length) {
            val n = index[text[i].code] + index[text[i + 1].code] * BASE
            if (n > MAX_SINGLE) return null
            out[o] = n.toByte()
        }
        return out
    }
}
