package com.difft.android.chat.mention

import com.difft.android.chat.contacts.contactsall.toPinyinSortKey
import org.difft.app.database.models.ContactorModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class MentionCandidateSorterTest {

    private val now = 1_000_000_000_000L
    private val h24 = 24L * 60 * 60 * 1000
    private val d14 = 14L * 24 * 60 * 60 * 1000

    private fun cand(
        uid: String,
        name: String = uid,
        nameNoRemark: String = name,
        base58: String = "TT-$uid",
    ) = MentionCandidate(uid, name, nameNoRemark, base58, name.toPinyinSortKey())

    private fun stat(
        lastMentionTime: Long,
        count24h: Int,
        count14d: Int,
        messageId: String = "m-$lastMentionTime",
    ) = MentionStats(lastMentionTime, messageId, count24h, count14d)

    private fun sort(
        candidates: List<MentionCandidate>,
        key: String? = null,
        mentionStats: Map<String, MentionStats> = emptyMap(),
        lastSpeakTime: Map<String, Long> = emptyMap(),
    ) = MentionCandidateSorter.sort(candidates, key, now, mentionStats, lastSpeakTime).map { it.uid }

    
    @Test
    fun `T1 full ordering across all four buckets`() {
        val candidates = listOf(
            cand("d2", name = "beta"), cand("c1"), cand("a2"), cand("b2"),
            cand("d1", name = "alpha"), cand("b1"), cand("c2"), cand("a1"),
        )
        val stats = mapOf(
            "a1" to stat(now - 1_000, 1, 1),                 
            "a2" to stat(now - 2_000, 1, 1),                 
            "b1" to stat(now - 2 * h24, 0, 5),               
            "b2" to stat(now - 3 * h24, 0, 2),               
        )
        val speak = mapOf("c1" to now - 1_000, "c2" to now - 5_000)
        assertEquals(
            listOf("a1", "a2", "b1", "b2", "c1", "c2", "d1", "d2"),
            sort(candidates, mentionStats = stats, lastSpeakTime = speak),
        )
    }

    
    @Test
    fun `T2 keyword filters out non-matching even if frequently mentioned`() {
        val candidates = listOf(
            cand("johny", name = "Johny"),
            cand("frank", name = "Frank"),
        )
        val stats = mapOf("frank" to stat(now - 1_000, 9, 9)) 
        assertEquals(listOf("johny"), sort(candidates, key = "jo", mentionStats = stats))
    }

    
    @Test
    fun `T3 match level ordering exact prefix contains`() {
        val candidates = listOf(
            cand("c", name = "Ryan", base58 = "TT-c"),     
            cand("a", name = "AN", base58 = "TT-a"),       
            cand("b", name = "Andrew", base58 = "TT-b"),   
        )
        assertEquals(listOf("a", "b", "c"), sort(candidates, key = "AN"))
    }

    
    @Test
    fun `T4 recent context wins in bucket A`() {
        val candidates = listOf(cand("a"), cand("b"))
        val stats = mapOf(
            "b" to stat(now - 1_000, 1, 1),      
            "a" to stat(now - 10_000, 3, 3),     
        )
        assertEquals(listOf("b", "a"), sort(candidates, mentionStats = stats))
    }

    
    @Test
    fun `T5 bucket B ordered by 14d count`() {
        val candidates = listOf(cand("a"), cand("b"))
        val stats = mapOf(
            "a" to stat(now - 2 * h24, 0, 1),
            "b" to stat(now - 2 * h24, 0, 10),
        )
        assertEquals(listOf("b", "a"), sort(candidates, mentionStats = stats))
    }

    
    @Test
    fun `T6 same message tie broken by 24h count`() {
        val candidates = listOf(cand("a"), cand("b"))
        val stats = mapOf(
            "a" to stat(now - 1_000, 3, 3),
            "b" to stat(now - 1_000, 1, 1),
        )
        assertEquals(listOf("a", "b"), sort(candidates, mentionStats = stats))
    }

    
    @Test
    fun `T7 tie broken by last speak`() {
        val candidates = listOf(cand("a"), cand("b"))
        val stats = mapOf(
            "a" to stat(now - 1_000, 2, 2),
            "b" to stat(now - 1_000, 2, 2),
        )
        val speak = mapOf("a" to now - 1_000, "b" to now - 5_000)
        assertEquals(listOf("a", "b"), sort(candidates, mentionStats = stats, lastSpeakTime = speak))
    }

    
    @Test
    fun `T8 pinyin fallback letters before hash group`() {
        val candidates = listOf(cand("n", name = "123"), cand("a", name = "alpha"))
        assertEquals(listOf("a", "n"), sort(candidates))
    }

    
    @Test
    fun `T9 empty candidates returns empty`() {
        assertEquals(emptyList(), sort(emptyList()))
    }

    
    @Test
    fun `T10 no match returns empty`() {
        val candidates = listOf(cand("a", name = "alpha"), cand("b", name = "beta"))
        assertEquals(emptyList(), sort(candidates, key = "zzz"))
    }

    
    @Test
    fun `T11 empty stats degrades to pinyin without crashing`() {
        val candidates = listOf(cand("c", name = "gamma"), cand("a", name = "alpha"), cand("b", name = "beta"))
        assertEquals(listOf("a", "b", "c"), sort(candidates))
    }

    
    @Test
    fun `T15 from maps display names and base58 id`() {
        val model = ContactorModel().apply {
            id = "10001"
            remark = "MyRemark"
            name = "RealName"
        }
        val c = MentionCandidate.from(model)
        assertEquals("MyRemark", c.displayName)          
        assertEquals("RealName", c.displayNameNoRemark)  
        assertEquals("10001", c.uid)
        assertTrue(c.base58Id.startsWith("TT-"))
    }
}
