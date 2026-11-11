package com.difft.android.chat.search


internal const val SNIPPET_LEAD = 8

private val LINE_BREAKS = Regex("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]")







fun searchSnippet(text: String, key: String): String {
    val line = text.replace(LINE_BREAKS, " ")
    if (key.isEmpty()) return line
    val hit = line.indexOf(key, ignoreCase = true)
    if (hit <= SNIPPET_LEAD) return line
    var start = hit - SNIPPET_LEAD
    
    if (line[start].isLowSurrogate()) start--
    return "…" + line.substring(start)
}
