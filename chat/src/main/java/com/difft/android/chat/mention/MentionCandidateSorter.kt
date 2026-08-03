package com.difft.android.chat.mention

import com.difft.android.chat.contacts.contactsall.PinyinSortKey

/**
 * Pure sorter for the @-mention candidate list (PRD §7). No Android/DB/coroutine deps, so
 * every rule is unit-testable. Filters (when a keyword is present) then orders candidates by
 * match level → relevance bucket → in-bucket tie-breaks → pinyin fallback.
 */
internal object MentionCandidateSorter {

    private const val WINDOW_24H_MS = 24L * 60 * 60 * 1000 // 86_400_000

    /**
     * Full PRD §7 sort.
     * @param key null/empty = "@" only (no filter, all members); non-empty = filter by match then sort.
     * @param now snapshot instant (same source as counts / 24h boundary).
     * @return filtered + ordered candidates (excludes @all; caller pins @all to the top). Empty = no match.
     */
    fun sort(
        candidates: List<MentionCandidate>,
        key: String?,
        now: Long,
        mentionStats: Map<String, MentionStats>,
        lastSpeakTime: Map<String, Long>,
    ): List<MentionCandidate> {
        val filtered = if (key.isNullOrEmpty()) candidates
        else candidates.filter { matchLevel(it, key) > 0 }
        // Compute each SortKey once (pinyin/CharacterParser is not cheap), then sort the pairs.
        return filtered
            .map { it to buildKey(it, key, now, mentionStats, lastSpeakTime) }
            .sortedWith(compareBy(COMPARATOR) { it.second })
            .map { it.first }
    }

    // --- Match level (only meaningful when key != null): EXACT(3) > PREFIX(2) > CONTAINS(1) > NONE(0). ---
    private fun matchLevel(c: MentionCandidate, key: String): Int {
        val fields = listOf(c.displayName, c.displayNameNoRemark, c.base58Id)
        return fields.maxOf { f ->
            when {
                f.equals(key, ignoreCase = true) -> 3
                f.startsWith(key, ignoreCase = true) -> 2
                f.contains(key, ignoreCase = true) -> 1
                else -> 0
            }
        }
    }

    /**
     * Folded key over all 4 relevance buckets. Fields that do not apply to a bucket are set to
     * neutral (0) so they produce no distinction in [COMPARATOR] for that bucket.
     *
     * bucket 0 (A): mentioned & lastMentionTime > now-24h  → lastMentionTime↓ count24h↓ count14d↓ lastSpeak↓ pinyin↑
     * bucket 1 (B): mentioned & lastMentionTime ≤ now-24h  → count14d↓ lastSpeak↓ pinyin↑
     * bucket 2 (C): not mentioned & spoke within 14d       → lastSpeak↓ pinyin↑
     * bucket 3 (D): nothing                                → pinyin↑
     */
    private data class SortKey(
        val matchLevel: Int,       // 3..0, descending (all 0 when key == null → no distinction)
        val bucket: Int,           // 0..3, ascending
        val lastMentionTime: Long, // bucket 0 only; else 0L
        val count24h: Int,         // bucket 0 only; else 0
        val count14d: Int,         // bucket 0 & 1; else 0
        val lastSpeak: Long,       // bucket 0/1/2; bucket 3 → 0L
        val pinyin: PinyinSortKey, // final fallback; ascending
    )

    private val COMPARATOR: Comparator<SortKey> =
        compareByDescending<SortKey> { it.matchLevel }
            .thenBy { it.bucket }
            .thenByDescending { it.lastMentionTime }
            .thenByDescending { it.count24h }
            .thenByDescending { it.count14d }
            .thenByDescending { it.lastSpeak }
            .thenBy { it.pinyin }

    private fun buildKey(
        c: MentionCandidate,
        key: String?,
        now: Long,
        mentionStats: Map<String, MentionStats>,
        lastSpeakTime: Map<String, Long>,
    ): SortKey {
        val stat = mentionStats[c.uid]
        val speak = lastSpeakTime[c.uid]
        val isRecent = stat != null && stat.lastMentionTime > now - WINDOW_24H_MS
        val bucket = when {
            isRecent -> 0
            stat != null -> 1
            speak != null -> 2
            else -> 3
        }
        return SortKey(
            matchLevel = if (key.isNullOrEmpty()) 0 else matchLevel(c, key),
            bucket = bucket,
            lastMentionTime = if (bucket == 0) stat!!.lastMentionTime else 0L,
            count24h = if (bucket == 0) stat!!.count24h else 0,
            count14d = if (bucket <= 1) stat!!.count14d else 0,
            lastSpeak = if (bucket <= 2) (speak ?: 0L) else 0L,
            pinyin = c.pinyinKey,
        )
    }
}
