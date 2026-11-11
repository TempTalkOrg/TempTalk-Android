package com.difft.android.chat.mention

import org.difft.app.database.models.MentionModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull




class MentionStatsAggregateTest {

    private val now = 1_000_000_000_000L
    private val h24 = 24L * 60 * 60 * 1000

    private fun mention(messageId: String, uid: String) =
        MentionModel().apply { this.messageId = messageId; this.uid = uid }

    
    @Test
    fun `T12 dedup and window split`() {
        val rows = listOf(
            mention("m1", "u1"),          
            mention("m1", "u1"),          
            mention("m2", "u1"),          
        )
        val tsByMsg = mapOf("m1" to now - 1_000, "m2" to now - 2 * h24)
        val result = aggregateMentionStats(rows, tsByMsg, now)
        val u1 = result["u1"]!!
        assertEquals(2, u1.count14d)                 
        assertEquals(1, u1.count24h)                 
        assertEquals(now - 1_000, u1.lastMentionTime) 
        assertEquals("m1", u1.lastMentionMessageId)
    }

    
    @Test
    fun `T13 dangling messages skipped`() {
        val rows = listOf(
            mention("m1", "u1"),       
            mention("gone", "u1"),     
            mention("gone2", "u2"),    
        )
        val tsByMsg = mapOf("m1" to now - 1_000)
        val result = aggregateMentionStats(rows, tsByMsg, now)
        assertNotNull(result["u1"])
        assertEquals(1, result["u1"]!!.count14d)
        assertNull(result["u2"])
    }

    
    @Test
    fun `T14 empty rows returns empty map`() {
        assertEquals(emptyMap(), aggregateMentionStats(emptyList(), emptyMap(), now))
    }
}
