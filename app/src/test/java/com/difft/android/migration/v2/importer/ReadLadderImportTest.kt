package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.InMemoryWcdbWriteTarget
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralReadPosition
import com.difft.android.migration.v2.model.NeutralRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Import side of the read-time ladder: the tri-state `expireStartedAt` lands in `readTime`
 * distinguishably, and the ladder is expanded over the rows that carry no anchor — the same
 * watermark-to-per-row expansion the live read path performs.
 */
class ReadLadderImportTest {

    private val selfUid = "u-self"
    private val room = "room-1"

    private fun row(maxServerTime: Long, readAt: Long) =
        NeutralReadPosition(readAt = readAt, maxServerTime = maxServerTime, maxNotifySequenceId = 0L, maxSequenceId = 0L)

    private fun message(
        id: String,
        serverAt: Long,
        expireStartedAt: Long? = null,
        sender: String = "u-alice",
        extras: Map<String, String> = emptyMap(),
    ) = NeutralRecord.Message(
        NeutralMessage(
            stableId = id,
            conversationRef = room,
            sender = sender,
            direction = if (sender == selfUid) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            body = "hi",
            sentAt = serverAt - 10,
            serverAt = serverAt,
            receivedAt = null,
            expireTimer = 0,
            expireStartedAt = expireStartedAt,
            contentKind = ContentKind.TEXT,
            systemType = null,
            confidential = false,
            platformExtras = extras,
        )
    )

    /**
     * A ladder with two rungs plus a watermark above both, imported together with messages spread
     * across every band: below the first rung, between the rungs, between the top rung and the
     * watermark, and above the watermark.
     */
    private fun importLadderFixture(records: List<NeutralRecord>): InMemoryWcdbWriteTarget {
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" }, selfUid = selfUid)
        importer.onRecord(
            NeutralRecord.ConversationLocal(
                NeutralConversationLocal(
                    conversationRef = room,
                    readPosition = row(maxServerTime = 500, readAt = 0),
                    readPositions = listOf(row(maxServerTime = 100, readAt = 200), row(maxServerTime = 300, readAt = 500)),
                )
            )
        )
        records.forEach { importer.onRecord(it) }
        importer.finish()
        return target
    }

    private fun readTimeOf(target: InMemoryWcdbWriteTarget, id: String): Long =
        target.messages.first { it.id == id }.readTime

    @Test
    fun `an explicit anchor lands verbatim and the ladder never overwrites it`() {
        val target = importLadderFixture(listOf(message("m-anchor", serverAt = 50, expireStartedAt = 777)))

        assertEquals(777L, readTimeOf(target, "m-anchor"))
    }

    @Test
    fun `an explicit zero becomes the not-started sentinel and the ladder leaves it alone`() {
        // -1 matches neither archive branch, so the message never expires by message time until a
        // real read event releases it.
        val target = importLadderFixture(listOf(message("m-not-started", serverAt = 50, expireStartedAt = 0)))

        assertEquals(-1L, readTimeOf(target, "m-not-started"))
    }

    @Test
    fun `a message covered by two rungs takes the smaller read time`() {
        // Rungs are applied by ascending readAt, so the write-once guard leaves the first (smallest)
        // covering value in place — the ladder lookup rule expressed as a sequence of writes.
        val target = importLadderFixture(
            listOf(message("m-low", serverAt = 50), message("m-mid", serverAt = 200))
        )

        assertEquals(200L, readTimeOf(target, "m-low"), "covered by both rungs → the smaller readAt")
        assertEquals(500L, readTimeOf(target, "m-mid"), "covered only by the upper rung")
    }

    @Test
    fun `the watermark fills the rows above the last rung and leaves the unread ones at zero`() {
        val target = importLadderFixture(
            listOf(message("m-gap", serverAt = 400), message("m-unread", serverAt = 900))
        )

        // The watermark carries no readAt, so its own server time stands in as the read moment.
        assertEquals(500L, readTimeOf(target, "m-gap"))
        assertEquals(0L, readTimeOf(target, "m-unread"), "above the watermark → unread, no anchor")
    }

    @Test
    fun `an outgoing row with no anchor keeps its send time and is not a ladder target`() {
        val target = importLadderFixture(listOf(message("m-out", serverAt = 50, sender = selfUid)))

        assertEquals(50L, readTimeOf(target, "m-out"))
    }

    @Test
    fun `the unplayed-voice extra restores the playStatus badge`() {
        val target = importLadderFixture(
            listOf(
                message("m-unplayed", serverAt = 50, extras = mapOf("android.played" to "false")),
                message("m-played", serverAt = 60),
            )
        )

        assertEquals(1, target.messages.first { it.id == "m-unplayed" }.playStatus)
        assertEquals(0, target.messages.first { it.id == "m-played" }.playStatus)
    }

    @Test
    fun `a conversation with only a watermark expands it over every read row`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" }, selfUid = selfUid)
        importer.onRecord(
            NeutralRecord.ConversationLocal(
                NeutralConversationLocal(
                    conversationRef = room,
                    readPosition = row(maxServerTime = 500, readAt = 4200),
                )
            )
        )
        importer.onRecord(message("m-read", serverAt = 100))
        importer.onRecord(message("m-unread", serverAt = 800))
        importer.finish()

        assertEquals(4200L, readTimeOf(target, "m-read"))
        assertEquals(0L, readTimeOf(target, "m-unread"))
    }
}
