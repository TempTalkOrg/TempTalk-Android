package com.difft.android.call.util

import java.text.BreakIterator

object StringUtil {

    
    fun truncateWithEllipsis(text: String, maxLength: Int): String {
        if (text.isEmpty() || maxLength <= 0 || text.length <= maxLength) return text

        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = iterator.first()
        repeat(maxLength) {
            val start = end
            end = iterator.next()
            if (end == BreakIterator.DONE) return text
            
            while (end < text.length && continuesEmojiCluster(text, start, end)) {
                end = iterator.next()
                if (end == BreakIterator.DONE) return text
            }
        }
        return if (end < text.length) text.substring(0, end) + "..." else text
    }

    private fun continuesEmojiCluster(text: String, start: Int, end: Int): Boolean {
        val next = text.codePointAt(end)
        val previous = text.codePointBefore(end)
        return next == 0x200D || previous == 0x200D ||
            next in 0x1F3FB..0x1F3FF || next in 0xE0020..0xE007F ||
            (previous in 0x1F1E6..0x1F1FF && next in 0x1F1E6..0x1F1FF &&
                text.codePointCount(start, end) % 2 == 1)
    }

    







    fun splitTextAndTrailingEmoji(input: String): Pair<String, String?> {
        if (input.isEmpty()) return input to null

        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(input)

        val end = iterator.last()
        val start = iterator.previous()
        if (start == java.text.BreakIterator.DONE) {
            return input to null
        }

        val lastGrapheme = input.substring(start, end)

        return if (isEmojiGrapheme(lastGrapheme)) {
            input.substring(0, start) to lastGrapheme
        } else {
            input to null
        }
    }


    


    fun isEmojiGrapheme(grapheme: String): Boolean {
        val codePoints = grapheme.codePoints().toArray()

        
        if (codePoints.any { it == 0xFE0F }) return true

        
        if (codePoints.any { it in 0x1F300..0x1FAFF }) return true

        
        if (codePoints.any { it in 0x2600..0x27BF }) return true

        
        if (codePoints.size == 2 &&
            codePoints.all { it in 0x1F1E6..0x1F1FF }
        ) return true

        return false
    }

}