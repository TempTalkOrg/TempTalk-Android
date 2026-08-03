package com.difft.android.base.security

/**
 * Minimal RFC 3492 Punycode decoder.
 *
 * Used as a fallback when Android's ICU-backed [java.net.IDN.toUnicode] declines
 * to decode a valid `xn--` label — its round-trip validation can fail (e.g. for
 * labels whose decoded form uses characters ICU rejects during re-encoding),
 * in which case it returns the ACE form unchanged. Decoding here lets us still
 * reveal the human-readable (Unicode) form of the host in the warning dialog.
 *
 * Decodes a single label WITHOUT the leading `xn--` prefix. Throws
 * [IllegalArgumentException] on malformed input; callers should wrap in
 * `runCatching` and fall back to the original label.
 */
internal object Punycode {

    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 0x80
    private const val DELIMITER = '-'
    private const val MAX_OUTPUT_CODE_POINTS = 256

    fun decode(input: String): String {
        // Code points accumulated so far. Using a list of code points (not a
        // StringBuilder) keeps insertion indices correct for supplementary chars.
        val output = ArrayList<Int>(input.length)

        var start = 0
        val lastDelimiter = input.lastIndexOf(DELIMITER)
        if (lastDelimiter >= 0) {
            for (j in 0 until lastDelimiter) {
                val c = input[j]
                require(c.code < 0x80) { "non-basic code point in basic segment" }
                output.add(c.code)
            }
            start = lastDelimiter + 1
        }

        var n = INITIAL_N.toLong()
        var i = 0L
        var bias = INITIAL_BIAS
        var pos = start

        while (pos < input.length) {
            val oldi = i
            var w = 1L
            var k = BASE
            while (true) {
                require(pos < input.length) { "unexpected end of input" }
                val digit = digitOf(input[pos++])
                require(digit < BASE) { "invalid digit" }
                i += digit * w
                require(i <= Int.MAX_VALUE) { "overflow" }
                val t = when {
                    k <= bias -> TMIN
                    k >= bias + TMAX -> TMAX
                    else -> k - bias
                }
                if (digit < t) break
                w *= (BASE - t)
                require(w <= Int.MAX_VALUE) { "overflow" }
                k += BASE
            }

            val outLen = output.size + 1
            bias = adapt((i - oldi).toInt(), outLen, oldi == 0L)
            n += i / outLen
            require(n in 0x80..0x10FFFF) { "code point out of range" }
            i %= outLen

            output.add(i.toInt(), n.toInt())
            require(output.size <= MAX_OUTPUT_CODE_POINTS) { "output too long" }
            i++
        }

        val sb = StringBuilder(output.size)
        for (cp in output) sb.appendCodePoint(cp)
        return sb.toString()
    }

    private fun digitOf(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> BASE
    }

    private fun adapt(deltaIn: Int, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= (BASE - TMIN)
            k += BASE
        }
        return k + ((BASE - TMIN + 1) * delta) / (delta + SKEW)
    }
}
