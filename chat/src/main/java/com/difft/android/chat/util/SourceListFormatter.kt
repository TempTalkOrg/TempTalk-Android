package com.difft.android.chat.util

import android.content.Context
import java.util.Locale











object SourceListFormatter {

    
    const val MAX_VISIBLE_AUTHORS = 5

    fun format(displayNames: List<String>, context: Context): String {
        if (displayNames.isEmpty()) return ""
        val locale = context.resources.configuration.locales[0]
        val shown = displayNames.take(MAX_VISIBLE_AUTHORS)
        val overflow = displayNames.size > MAX_VISIBLE_AUTHORS
        return if (locale.language == Locale.CHINESE.language || locale.language == "zh") {
            formatChinese(shown, totalCount = displayNames.size, overflow)
        } else {
            formatEnglish(shown, totalCount = displayNames.size, overflow)
        }
    }

    private fun formatChinese(shown: List<String>, totalCount: Int, overflow: Boolean): String {
        val joined = shown.joinToString("、")
        
        return if (overflow) "$joined 等 $totalCount 人" else joined
    }

    private fun formatEnglish(shown: List<String>, totalCount: Int, overflow: Boolean): String {
        
        
        
        
        if (overflow) {
            val others = totalCount - shown.size
            val noun = if (others == 1) "other" else "others"
            return "${shown.joinToString(", ")} and $others $noun"
        }
        
        
        
        
        return when (shown.size) {
            1 -> shown[0]
            2 -> "${shown[0]} and ${shown[1]}"
            in 3..MAX_VISIBLE_AUTHORS -> shown.dropLast(1).joinToString(", ") + ", and ${shown.last()}"
            else -> error("shown should be in 1..$MAX_VISIBLE_AUTHORS")
        }
    }
}
