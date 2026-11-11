package com.difft.android.chat.mention

import com.difft.android.chat.contacts.contactsall.PinyinSortKey




internal object MentionCandidateSorter {

    private const val WINDOW_24H_MS = 24L * 60 * 60 * 1000 

    





    fun sort(
        candidates: List<MentionCandidate>,
        key: String?,
        now: Long,
        mentionStats: Map<String, MentionStats>,
        lastSpeakTime: Map<String, Long>,
    ): List<MentionCandidate> {
        val filtered = if (key.isNullOrEmpty()) candidates
        else candidates.filter { matchLevel(it, key) > 0 }
        
        return filtered
            .map { it to buildKey(it, key, now, mentionStats, lastSpeakTime) }
            .sortedWith(compareBy(COMPARATOR) { it.second })
            .map { it.first }
    }

    
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

    








    private data class SortKey(
        val matchLevel: Int,       
        val bucket: Int,           
        val lastMentionTime: Long, 
        val count24h: Int,         
        val count14d: Int,         
        val lastSpeak: Long,       
        val pinyin: PinyinSortKey, 
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
