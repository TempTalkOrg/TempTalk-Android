package com.difft.android.chat.ui

import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the #909 read-receipt accumulation + chunking logic.
 *
 * These invoke the production seam [ChatMessageViewModel.accumulateReadReceipt] directly
 * (a pure fold over a message sequence) and the production chunk cap
 * [ChatMessageViewModel.MAX_TIMESTAMPS_PER_RECEIPT] via stdlib `chunked`, exactly as
 * `sendReadRecipient` does. No WCDB / native libs required — [MessageModel] is a plain POJO.
 *
 * Covers design test cases T14 (per-sender accumulation + max), T15 (chunking math),
 * T16 (global max selection), T17 (empty range). The DB paging itself ([forEachMessagePaged])
 * and the 3 WCDB helpers are exercised by the @Ignore-d integration tests in :database
 * (WCDB native libs are not loadable in JVM unit tests — see WCDBPagedMessageAccessTest).
 */
class ReadReceiptAccumulationTest {

    private fun msg(from: String, ts: Long, sysTs: Long = ts): MessageModel =
        MessageModel().apply {
            fromWho = from
            timeStamp = ts
            systemShowTimestamp = sysTs
            notifySequenceId = sysTs
            sequenceId = sysTs
        }

    /** Mirrors the production paging loop: fold messages + track global max. */
    private fun accumulate(messages: List<MessageModel>): Pair<HashMap<String, SenderReadReceiptAcc>, MessageModel?> {
        val perSender = HashMap<String, SenderReadReceiptAcc>()
        var globalMax: MessageModel? = null
        messages.forEach { m ->
            ChatMessageViewModel.accumulateReadReceipt(perSender, m)
            val cur = globalMax
            if (cur == null || m.systemShowTimestamp > cur.systemShowTimestamp) globalMax = m
        }
        return perSender to globalMax
    }

    // T14 — per-sender accumulation: timestamps grouped per sender, max tracked
    @Test
    fun `groups timestamps per sender and tracks max message`() {
        val messages = listOf(
            msg("A", 100), msg("B", 150), msg("A", 200), msg("A", 50)
        )
        val (perSender, _) = accumulate(messages)

        assertEquals(setOf("A", "B"), perSender.keys)
        assertEquals(listOf(100L, 200L, 50L), perSender["A"]!!.timeStamps)
        assertEquals(200L, perSender["A"]!!.maxMessage.systemShowTimestamp)
        assertEquals(listOf(150L), perSender["B"]!!.timeStamps)
        assertEquals(150L, perSender["B"]!!.maxMessage.systemShowTimestamp)
    }

    // T16 — global max is the globally-latest message across all senders
    @Test
    fun `global max is the latest message across all senders`() {
        val messages = listOf(msg("A", 100), msg("B", 500), msg("A", 300))
        val (_, globalMax) = accumulate(messages)
        val max = checkNotNull(globalMax)

        assertEquals(500L, max.systemShowTimestamp)
        assertEquals("B", max.fromWho)
    }

    // T17 — empty range produces no senders and no global max
    @Test
    fun `empty range yields empty accumulator and null global max`() {
        val (perSender, globalMax) = accumulate(emptyList())
        assertTrue(perSender.isEmpty())
        assertEquals(null, globalMax)
    }

    // T15 — chunking: a sender over the cap is split into multiple Jobs, union == full set
    @Test
    fun `timestamps over the cap split into multiple chunks covering the full set`() {
        val cap = ChatMessageViewModel.MAX_TIMESTAMPS_PER_RECEIPT
        val total = cap + 50
        val messages = (1..total).map { msg("A", it.toLong()) }
        val (perSender, _) = accumulate(messages)

        val chunks = perSender["A"]!!.timeStamps.chunked(cap)
        assertEquals(2, chunks.size)                       // (cap+50)/cap rounds up to 2 Jobs
        assertEquals(cap, chunks[0].size)
        assertEquals(50, chunks[1].size)
        chunks.forEach { assertTrue(it.size <= cap) }
        // union of chunks == full timestamp set, in order
        assertEquals((1L..total.toLong()).toList(), chunks.flatten())
    }

    // T15 boundary — exactly at the cap stays a single chunk
    @Test
    fun `timestamps exactly at the cap stay a single chunk`() {
        val cap = ChatMessageViewModel.MAX_TIMESTAMPS_PER_RECEIPT
        val messages = (1..cap).map { msg("A", it.toLong()) }
        val (perSender, _) = accumulate(messages)

        val chunks = perSender["A"]!!.timeStamps.chunked(cap)
        assertEquals(1, chunks.size)
        assertEquals(cap, chunks[0].size)
    }

    // T18 — large-group branch: planReadReceiptJobs returns NO per-sender jobs when
    // isLargeGroup=true. The caller still emits exactly one sync job (not part of this
    // pure plan). This pins the large-group skip so it can't silently regress.
    @Test
    fun `large group plans no per-sender receipt jobs`() {
        val (perSender, _) = accumulate(listOf(msg("A", 100), msg("B", 200)))

        val plans = ChatMessageViewModel.planReadReceiptJobs(perSender, isLargeGroup = true)
        assertTrue(plans.isEmpty())
    }

    // T18 (complement) — small group: one plan per sender, recipient + max + full timestamp
    // list carried, derived directly from the accumulator (verifies the non-large branch).
    @Test
    fun `small group plans one job per sender carrying its timestamps and max`() {
        val (perSender, _) = accumulate(
            listOf(msg("A", 100), msg("B", 150), msg("A", 200))
        )

        val plans = ChatMessageViewModel.planReadReceiptJobs(perSender, isLargeGroup = false)

        assertEquals(2, plans.size)
        val byRecipient = plans.associateBy { it.recipientId }
        assertEquals(listOf(100L, 200L), byRecipient["A"]!!.timeStamps)
        assertEquals(200L, byRecipient["A"]!!.maxMessage.systemShowTimestamp)
        assertEquals(listOf(150L), byRecipient["B"]!!.timeStamps)
        assertEquals(150L, byRecipient["B"]!!.maxMessage.systemShowTimestamp)
    }

    // T15/T18 — chunking via the production plan: a sender over the cap yields multiple plans,
    // all sharing the same max message; union of plan timestamps == full set, in order.
    @Test
    fun `plan splits an over-cap sender into multiple jobs sharing one read position`() {
        val cap = ChatMessageViewModel.MAX_TIMESTAMPS_PER_RECEIPT
        val total = cap + 50
        val (perSender, _) = accumulate((1..total).map { msg("A", it.toLong()) })

        val plans = ChatMessageViewModel.planReadReceiptJobs(perSender, isLargeGroup = false)

        assertEquals(2, plans.size)                                   // (cap+50)/cap rounds up to 2
        plans.forEach { assertTrue(it.timeStamps.size <= cap) }
        // all chunks for the sender share the same max message ⇒ same ReadPosition
        assertEquals(1, plans.map { it.maxMessage }.distinct().size)
        // union of chunks == full timestamp set, in order
        assertEquals((1L..total.toLong()).toList(), plans.flatMap { it.timeStamps })
    }
}
