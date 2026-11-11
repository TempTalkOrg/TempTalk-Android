package util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object StringUtil {

    private val WHITESPACE: Set<Char> = SetUtil.newHashSet(
        '\u200E', 
        '\u200F', 
        '\u2007', 
        '\u200B', 
        '\u2800'  
    )

    private val ALL_ASCII_PATTERN: Pattern = Pattern.compile("^[\\x00-\\x7F]*$")

    private object Bidi {
        
        val OVERRIDES: Set<Int> = SetUtil.newHashSet(
            "\u202A".codePointAt(0), 
            "\u202B".codePointAt(0), 
            "\u202D".codePointAt(0), 
            "\u202E".codePointAt(0)  
        )

        
        val ISOLATES: Set<Int> = SetUtil.newHashSet(
            "\u2066".codePointAt(0), 
            "\u2067".codePointAt(0), 
            "\u2068".codePointAt(0)  
        )

        
        val PDF = "\u202C".codePointAt(0)

        
        val PDI = "\u2069".codePointAt(0)

        
        val FSI = "\u2068".codePointAt(0)
    }

    





    @JvmStatic
    fun trimToFit(name: String?, maxByteLength: Int): String {
        if (name.isNullOrEmpty()) {
            return ""
        }

        if (name.toByteArray(StandardCharsets.UTF_8).size <= maxByteLength) {
            return name
        }

        return try {
            ByteArrayOutputStream().use { stream ->
                for (graphemeCharacter in CharacterIterable(name)) {
                    val bytes = graphemeCharacter.toByteArray(StandardCharsets.UTF_8)

                    if (stream.size() + bytes.size <= maxByteLength) {
                        stream.write(bytes)
                    } else {
                        break
                    }
                }
                stream.toString()
            }
        } catch (e: IOException) {
            throw AssertionError(e)
        }
    }

    



    @JvmStatic
    fun trim(charSequence: CharSequence): CharSequence {
        if (charSequence.isEmpty()) {
            return charSequence
        }

        var start = 0
        var end = charSequence.length - 1

        while (start < charSequence.length && Character.isWhitespace(charSequence[start])) {
            start++
        }

        while (end >= 0 && end > start && Character.isWhitespace(charSequence[end])) {
            end--
        }

        return if (start > 0 || end < charSequence.length - 1) {
            charSequence.subSequence(start, end + 1)
        } else {
            charSequence
        }
    }

    



    @JvmStatic
    fun isVisuallyEmpty(value: String?): Boolean {
        if (value.isNullOrEmpty()) {
            return true
        }

        return indexOfFirstNonEmptyChar(value) == -1
    }

    



    @JvmStatic
    fun trimToVisualBounds(value: String): String {
        val start = indexOfFirstNonEmptyChar(value)

        if (start == -1) {
            return ""
        }

        val end = indexOfLastNonEmptyChar(value)

        return value.substring(start, end + 1)
    }

    private fun indexOfFirstNonEmptyChar(value: String): Int {
        val length = value.length

        for (i in 0 until length) {
            if (!isVisuallyEmpty(value[i])) {
                return i
            }
        }

        return -1
    }

    private fun indexOfLastNonEmptyChar(value: String): Int {
        for (i in value.length - 1 downTo 0) {
            if (!isVisuallyEmpty(value[i])) {
                return i
            }
        }
        return -1
    }

    



    @JvmStatic
    fun isVisuallyEmpty(c: Char): Boolean {
        return Character.isWhitespace(c) || WHITESPACE.contains(c)
    }

    


    @JvmStatic
    fun codePointToString(codePoint: Int): String {
        return String(Character.toChars(codePoint))
    }

    


    @JvmStatic
    fun hasMixedTextDirection(text: CharSequence?): Boolean {
        if (text == null) {
            return false
        }

        var isLtr: Boolean? = null

        var i = 0
        val len = Character.codePointCount(text, 0, text.length)
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            val direction = Character.getDirectionality(codePoint)
            val isLetter = Character.isLetter(codePoint)

            if (isLtr != null && isLtr && direction != Character.DIRECTIONALITY_LEFT_TO_RIGHT && isLetter) {
                return true
            } else if (isLtr != null && !isLtr && direction != Character.DIRECTIONALITY_RIGHT_TO_LEFT && isLetter) {
                return true
            } else if (isLetter) {
                isLtr = direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT
            }
            i++
        }

        return false
    }

    


    @JvmStatic
    fun isEmpty(text: String?): Boolean {
        return text == null || text.isEmpty()
    }

    












    @JvmStatic
    fun isolateBidi(text: String?): String {
        if (text == null) {
            return ""
        }

        if (isEmpty(text)) {
            return text
        }

        if (ALL_ASCII_PATTERN.matcher(text).matches()) {
            return text
        }

        var overrideCount = 0
        var overrideCloseCount = 0
        var isolateCount = 0
        var isolateCloseCount = 0

        var i = 0
        val len = text.codePointCount(0, text.length)
        while (i < len) {
            val codePoint = text.codePointAt(i)

            if (Bidi.OVERRIDES.contains(codePoint)) {
                overrideCount++
            } else if (codePoint == Bidi.PDF) {
                overrideCloseCount++
            } else if (Bidi.ISOLATES.contains(codePoint)) {
                isolateCount++
            } else if (codePoint == Bidi.PDI) {
                isolateCloseCount++
            }
            i++
        }

        val suffix = StringBuilder()

        while (overrideCount > overrideCloseCount) {
            suffix.appendCodePoint(Bidi.PDF)
            overrideCloseCount++
        }

        while (isolateCount > isolateCloseCount) {
            suffix.appendCodePoint(Bidi.FSI)
            isolateCloseCount++
        }

        val out = StringBuilder()

        return out.appendCodePoint(Bidi.FSI)
            .append(text)
            .append(suffix)
            .appendCodePoint(Bidi.PDI)
            .toString()
    }

    @JvmStatic
    fun stripBidiProtection(text: String?): String? {
        if (text == null) return null

        return text.replace(Regex("[\\u2068\\u2069\\u202c]"), "")
    }

    @JvmStatic
    fun stripBidiIndicator(text: String): String {
        return text.replace("\u200F", "")
    }

    



    @JvmStatic
    fun trimSequence(text: CharSequence): CharSequence {
        var length = text.length
        var startIndex = 0

        while ((startIndex < length) && (text[startIndex] <= ' ')) {
            startIndex++
        }
        while ((startIndex < length) && (text[length - 1] <= ' ')) {
            length--
        }
        return if (startIndex > 0 || length < text.length) text.subSequence(startIndex, length) else text
    }

    







    @JvmStatic
    fun abbreviateInMiddle(text: CharSequence?, maxChars: Int): CharSequence? {
        if (text == null || text.length <= maxChars) {
            return text
        }

        val start = (maxChars - 1) / 2
        val end = (maxChars - 1) - start
        return text.subSequence(0, start).toString() + "…" + text.subSequence(text.length - end, text.length)
    }

    


    @JvmStatic
    fun getGraphemeCount(text: CharSequence): Int {
        val iterator = BreakIteratorCompat.getInstance()
        iterator.setText(text)
        return iterator.countBreaks()
    }
}
