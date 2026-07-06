package com.difft.android.chat.util

import android.content.Context
import java.util.Locale

/**
 * Builds the locale-specific author list string used as `%3$s` in notice plurals,
 * per PRD v1.0 §5.3.4.
 *
 *   - Chinese: names joined by `、`, overflow suffix `等 N 人` (N = total authors)
 *   - English: names joined by `, ` with Oxford comma for size in [3..5]
 *              and `and` connector for size == 2; overflow suffix
 *              `and 1 other` (N == 1) / `and N others` (N > 1), where
 *              N = total - shown.size (unshown count)
 *
 * `displayNames` is the already-resolved list (myId → "You" / remark → name → base58).
 * Caller is responsible for ordering per PRD §5.3.4 (use NoticeAggregator).
 */
object SourceListFormatter {

    /** Cap on the number of names spelled out before overflow suffix kicks in. */
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
        // PRD §5.3.4: "等 N 人" — space between N and 人.
        return if (overflow) "$joined 等 $totalCount 人" else joined
    }

    private fun formatEnglish(shown: List<String>, totalCount: Int, overflow: Boolean): String {
        // PRD §5.3.4: English overflow is "and N other(s)" where N = total - shown.size.
        // In overflow mode the trailing "and N other(s)" already serves as the connector,
        // so the shown names are joined with plain ", " (NO Oxford comma — that would
        // produce "..., and X and N others" reading as nested clauses).
        if (overflow) {
            val others = totalCount - shown.size
            val noun = if (others == 1) "other" else "others"
            return "${shown.joinToString(", ")} and $others $noun"
        }
        // No overflow: English grammar applies.
        //   1 → "A"
        //   2 → "A and B"           (no Oxford comma)
        //   3..5 → "A, B, and C"    (Oxford comma)
        return when (shown.size) {
            1 -> shown[0]
            2 -> "${shown[0]} and ${shown[1]}"
            in 3..MAX_VISIBLE_AUTHORS -> shown.dropLast(1).joinToString(", ") + ", and ${shown.last()}"
            else -> error("shown should be in 1..$MAX_VISIBLE_AUTHORS")
        }
    }
}
