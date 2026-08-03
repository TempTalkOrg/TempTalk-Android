package com.difft.android.chat.mention

import org.difft.app.database.models.MentionModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Pure unit tests for [aggregateMentionStats] (design Test Case Inventory T12–T14). No WCDB deps.
 */
class MentionStatsAggregateTest {

    private val now = 1_000_000_000_000L
    private val h24 = 24L * 60 * 60 * 1000

    private fun mention(messageId: String, uid: String) =
        MentionModel().apply { this.messageId = messageId; this.uid = uid }

    // T12 — de-dup per (messageId,uid); 24h/14d window split; latest message drives lastMentionTime
    @Test
    fun `T12 dedup and window split`() {
        val rows = listOf(
            mention("m1", "u1"),          // within 24h
            mention("m1", "u1"),          // duplicate row on same message → counts once
            mention("m2", "u1"),          // >24h, within 14d
        )
        val tsByMsg = mapOf("m1" to now - 1_000, "m2" to now - 2 * h24)
        val result = aggregateMentionStats(rows, tsByMsg, now)
        val u1 = result["u1"]!!
        assertEquals(2, u1.count14d)                 // m1 + m2 (dup removed)
        assertEquals(1, u1.count24h)                 // only m1 within 24h
        assertEquals(now - 1_000, u1.lastMentionTime) // newest message
        assertEquals("m1", u1.lastMentionMessageId)
    }

    // T13 — dangling messageId (missing from tsByMsg) is skipped, no NPE / empty stat
    @Test
    fun `T13 dangling messages skipped`() {
        val rows = listOf(
            mention("m1", "u1"),       // valid
            mention("gone", "u1"),     // dangling → ignored for u1
            mention("gone2", "u2"),    // u2 only dangling → dropped entirely
        )
        val tsByMsg = mapOf("m1" to now - 1_000)
        val result = aggregateMentionStats(rows, tsByMsg, now)
        assertNotNull(result["u1"])
        assertEquals(1, result["u1"]!!.count14d)
        assertNull(result["u2"])
    }

    // T14 — empty input → empty map
    @Test
    fun `T14 empty rows returns empty map`() {
        assertEquals(emptyMap(), aggregateMentionStats(emptyList(), emptyMap(), now))
    }
}
