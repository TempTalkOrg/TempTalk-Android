package com.difft.android.base.utils



private val NEWLINE_FAMILY = Regex("\\r\\n|[\\r\\u000B\\u000C\\u0085\\u2028\\u2029]")





fun String.normalizeNewlines(): String = replace(NEWLINE_FAMILY, "\n")



private val DISPLAY_NEWLINES = Regex("\\r(?!\\n)|[\\u000B\\u000C\\u0085\\u2028\\u2029]")






fun String.normalizeNewlinesForDisplay(): String = replace(DISPLAY_NEWLINES, "\n")
