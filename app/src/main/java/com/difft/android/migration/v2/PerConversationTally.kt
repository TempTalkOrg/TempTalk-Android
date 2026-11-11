package com.difft.android.migration.v2









internal object PerConversationTally {

    
    const val MAX_ENTRIES = 50

    



    fun format(counts: Map<String, Int>): String {
        if (counts.isEmpty()) return "{}"
        if (counts.size <= MAX_ENTRIES) {
            return counts.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { "${it.key}:${it.value}" }
        }
        val top = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_ENTRIES)
        val hidden = counts.size - MAX_ENTRIES
        return top.joinToString(prefix = "{", postfix = ", ... (+$hidden more conversations not shown)}") {
            "${it.key}:${it.value}"
        }
    }
}
