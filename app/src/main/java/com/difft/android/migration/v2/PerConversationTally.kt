package com.difft.android.migration.v2

/**
 * Formats a per-conversation message tally for the migration export/import diagnostic logs.
 *
 * Exporter and importer share this so both emit the identical `{roomId:count, ...}` shape and the
 * two log lines can be diffed to find where a conversation's messages dropped. Output is sorted for
 * stable lines, bounded at [MAX_ENTRIES], and any truncation is stated rather than silent. Only
 * roomIds and counts are logged — never message bodies or attachment keys.
 */
internal object PerConversationTally {

    /** Max conversations rendered inline before truncating to the top-N by count. */
    const val MAX_ENTRIES = 50

    /**
     * Renders [counts] as `{roomId:count, ...}`, sorted by roomId. Over [MAX_ENTRIES] it renders the
     * top entries by count and appends an explicit `+<n> more conversations not shown` note.
     */
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
