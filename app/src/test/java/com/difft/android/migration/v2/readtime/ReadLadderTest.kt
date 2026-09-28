package com.difft.android.migration.v2.readtime

import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralReadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-ladder contract, on both sides: what Android's per-message read times fold into on export,
 * and what a peer's ladder normalizes to on import. Pure JVM — the ladder carries no Android, WCDB or
 * logging dependency, so every case here is exact arithmetic rather than a DB round trip.
 */
class ReadLadderTest {

    private fun read(serverAt: Long, readTime: Long) = ReadLadderBuilder.IncomingRead(serverAt, readTime)

    private fun row(maxServerTime: Long, readAt: Long) =
        NeutralReadPosition(readAt = readAt, maxServerTime = maxServerTime, maxNotifySequenceId = 0L, maxSequenceId = 0L)

    /** `(maxServerTime, readAt)` pairs, the shape every assertion below reads in. */
    private fun ReadLadder.pairs(): List<Pair<Long, Long>> = rows.map { it.maxServerTime to it.readAt }

    // ── export: rows out of Android's expanded read times ────────────────────────────────────

    @Test
    fun `one batch read becomes one rung ending at its last message`() {
        val ladder = ReadLadderBuilder.build(
            listOf(read(100, 500), read(200, 500), read(300, 700)),
            roomReadPosition = 300,
        )

        assertEquals(listOf(200L to 500L, 300L to 700L), ladder.pairs())
    }

    @Test
    fun `rungs sharing a server time collapse to the smallest read time`() {
        val ladder = ReadLadderBuilder.build(listOf(read(100, 500), read(100, 400)), roomReadPosition = 100)

        assertEquals(listOf(100L to 400L), ladder.pairs())
    }

    @Test
    fun `a later rung read earlier pulls the rungs before it down`() {
        // Suffix-min: a message can never be handed a read time later than one a covering rung gives.
        val ladder = ReadLadderBuilder.build(
            listOf(read(100, 900), read(200, 300), read(300, 1000)),
            roomReadPosition = 300,
        )

        assertEquals(listOf(200L to 300L, 300L to 1000L), ladder.pairs())
        assertEquals(300L, ladder.lookup(50))
        assertEquals(300L, ladder.lookup(150))
        assertEquals(1000L, ladder.lookup(250))
    }

    @Test
    fun `rungs the suffix-min pass equalized merge into the one reaching furthest`() {
        val ladder = ReadLadderBuilder.build(
            listOf(read(100, 500), read(200, 900), read(300, 500)),
            roomReadPosition = 300,
        )

        assertEquals(listOf(300L to 500L), ladder.pairs())
    }

    @Test
    fun `the watermark never undercuts the last rung`() {
        // The gap between the last rung and the watermark must not expire before the rung itself.
        val ladder = ReadLadderBuilder.build(listOf(read(100, 1000)), roomReadPosition = 200)

        assertEquals(200L, ladder.watermark?.maxServerTime)
        assertEquals(1000L, ladder.watermark?.readAt)
        assertEquals(1000L, ladder.effectiveWatermarkReadAt())
        assertTrue(ladder.effectiveWatermarkReadAt()!! >= ladder.rows.last().readAt)
    }

    @Test
    fun `no reads and no watermark means no derived start at all`() {
        val ladder = ReadLadderBuilder.build(emptyList(), roomReadPosition = 0)

        assertTrue(ladder.rows.isEmpty())
        assertNull(ladder.watermark)
        assertNull(ladder.derivedStart(MessageDirection.INCOMING, 100))
        assertEquals(100L, ladder.derivedStart(MessageDirection.OUTGOING, 100))
    }

    // ── export self-check: which rows still need an explicit anchor ──────────────────────────

    @Test
    fun `a late message keeps its own low read time and the batch around it carries exceptions`() {
        // A message that arrived after the watermark had passed it is inserted with readTime =
        // roomReadPosition, so it sits as a lower rung inside a batch. The rung is NOT lifted — the
        // messages whose derived start it pulls down travel an explicit anchor instead.
        val reads = listOf(read(100, 5000), read(150, 300), read(200, 5000), read(300, 5000))
        val ladder = ReadLadderBuilder.build(reads, roomReadPosition = 300)

        assertEquals(listOf(150L to 300L, 300L to 5000L), ladder.pairs())

        assertEquals(5000L, anchorOf(readTime = 5000, serverAt = 100, ladder = ladder), "batch message before the late one")
        assertNull(anchorOf(readTime = 300, serverAt = 150, ladder = ladder), "the late message itself is derivable")
        assertNull(anchorOf(readTime = 5000, serverAt = 200, ladder = ladder), "after the late rung the batch derives")
        assertNull(anchorOf(readTime = 5000, serverAt = 300, ladder = ladder))
    }

    @Test
    fun `a second device's decreasing watermark normalizes and the rows it lowered carry exceptions`() {
        val ladder = ReadLadderBuilder.build(listOf(read(100, 1000), read(200, 300)), roomReadPosition = 200)

        assertEquals(listOf(200L to 300L), ladder.pairs(), "the 1000 rung is normalized down to 300")
        assertEquals(1000L, anchorOf(readTime = 1000, serverAt = 100, ladder = ladder))
        assertNull(anchorOf(readTime = 300, serverAt = 200, ladder = ladder))
    }

    @Test
    fun `a legacy zero row inside the watermark ages by arrival and travels only when that differs`() {
        // readTime = 0 below the watermark is the pre-#1020 legacy branch: the sweep ages it by
        // arrival, so its effective start is its own server time.
        val ladder = ReadLadderBuilder.build(listOf(read(300, 500)), roomReadPosition = 300)

        assertEquals(150L, anchorOf(readTime = 0, serverAt = 150, ladder = ladder, roomReadPosition = 300))

        val matching = ReadLadder(listOf(row(maxServerTime = 300, readAt = 150)), row(maxServerTime = 300, readAt = 150))
        assertNull(
            anchorOf(readTime = 0, serverAt = 150, ladder = matching, roomReadPosition = 300),
            "no exception when the ladder already derives the same start",
        )
    }

    @Test
    fun `the not-started sentinel travels as an explicit zero only when the ladder would override it`() {
        val covering = ReadLadderBuilder.build(listOf(read(300, 500)), roomReadPosition = 300)
        assertEquals(0L, anchorOf(readTime = -1, serverAt = 150, ladder = covering))

        val unread = ReadLadderBuilder.build(emptyList(), roomReadPosition = 0)
        assertNull(anchorOf(readTime = -1, serverAt = 150, ladder = unread))
    }

    @Test
    fun `an unread incoming message has no start and carries nothing`() {
        val ladder = ReadLadderBuilder.build(listOf(read(100, 500)), roomReadPosition = 100)

        assertNull(ladder.derivedStart(MessageDirection.INCOMING, 900))
        assertNull(anchorOf(readTime = 0, serverAt = 900, ladder = ladder, roomReadPosition = 100))
    }

    @Test
    fun `an outgoing row anchored at its send time is fully derivable`() {
        val ladder = ReadLadderBuilder.build(listOf(read(100, 500)), roomReadPosition = 100)

        assertNull(
            AndroidExpiryAnchor.exception(
                local = AndroidExpiryAnchor.of(readTime = 2000, serverAt = 2000, direction = MessageDirection.OUTGOING, roomReadPosition = 100),
                derived = ladder.derivedStart(MessageDirection.OUTGOING, 2000),
            )
        )
    }

    // ── import: normalizing whatever a peer sent ─────────────────────────────────────────────

    @Test
    fun `fromWire sorts, dedupes and re-normalizes whatever order a peer sent`() {
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 400, readAt = 9000),
            readPositions = listOf(
                row(maxServerTime = 300, readAt = 700),
                row(maxServerTime = 100, readAt = 900),   // out of order, and read later than a later rung
                row(maxServerTime = 100, readAt = 950),   // duplicate server time
            ),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(listOf(100L to 700L, 300L to 700L), ladder.pairs())
        assertEquals(9000L, ladder.effectiveWatermarkReadAt())
    }

    @Test
    fun `fromWire clamps a rung that reaches past the watermark`() {
        // A rung above the watermark would hand an expiry anchor to a message the target still counts
        // as unread, so it is pulled back to the watermark.
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 400, readAt = 0),
            readPositions = listOf(row(maxServerTime = 100, readAt = 400), row(maxServerTime = 500, readAt = 600)),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(listOf(100L to 400L, 400L to 600L), ladder.pairs())
        assertTrue(ladder.rows.last().maxServerTime <= ladder.watermark!!.maxServerTime, "no rung past the watermark")
    }

    @Test
    fun `fromWire substitutes its own server time for a rung with no read clock`() {
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 200, readAt = 0),
            readPositions = listOf(row(maxServerTime = 100, readAt = 0)),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(listOf(100L to 100L), ladder.pairs())
        assertEquals(100L, ladder.derivedStart(MessageDirection.INCOMING, 50))
    }

    @Test
    fun `a rung with no read clock is substituted before normalizing, so its neighbours keep theirs`() {
        // Substituting first bounds the earlier rungs by the substituted value (200) instead of
        // collapsing each to its own server time (100), and leaves the rungs above it untouched.
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 300, readAt = 0),
            readPositions = listOf(
                row(maxServerTime = 100, readAt = 500),
                row(maxServerTime = 200, readAt = 0),
                row(maxServerTime = 300, readAt = 700),
            ),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(listOf(100L to 200L, 200L to 200L, 300L to 700L), ladder.pairs())
        // The lookup rule is the minimum readAt over every covering rung, so the substituted 200
        // still bounds a message below it — but at 200, not at the row's own 100.
        assertEquals(200L, ladder.lookup(50))
        assertEquals(200L, ladder.lookup(150))
        assertEquals(700L, ladder.lookup(250), "a rung above the substituted one is unaffected")
    }

    @Test
    fun `the pruning-order counterexample resolves to the smallest covering read time`() {
        // §2.3: rows (100,300) and (200,210); a message at 90 is covered by both and must get 210.
        // Dropping the second rung before normalizing would give it 300.
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 200, readAt = 0),
            readPositions = listOf(row(maxServerTime = 100, readAt = 300), row(maxServerTime = 200, readAt = 210)),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(210L, ladder.lookup(90))
        assertEquals(210L, ladder.derivedStart(MessageDirection.INCOMING, 90))
    }

    @Test
    fun `a message between the last rung and the watermark falls back to the watermark`() {
        val wire = NeutralConversationLocal(
            conversationRef = "room-1",
            readPosition = row(maxServerTime = 900, readAt = 0),
            readPositions = listOf(row(maxServerTime = 100, readAt = 400)),
        )

        val ladder = ReadLadder.fromWire(wire)

        assertEquals(400L, ladder.derivedStart(MessageDirection.INCOMING, 100))
        // readAt 0 on the watermark means "no read clock", so its own server time stands in.
        assertEquals(900L, ladder.derivedStart(MessageDirection.INCOMING, 500))
        assertNull(ladder.derivedStart(MessageDirection.INCOMING, 901))
    }

    /** The wire `expireStartedAt` an Android row with these values would carry against [ladder]. */
    private fun anchorOf(
        readTime: Long,
        serverAt: Long,
        ladder: ReadLadder,
        direction: MessageDirection = MessageDirection.INCOMING,
        roomReadPosition: Long = 0,
    ): Long? = AndroidExpiryAnchor.exception(
        local = AndroidExpiryAnchor.of(readTime, serverAt, direction, roomReadPosition),
        derived = ladder.derivedStart(direction, serverAt),
    )
}
