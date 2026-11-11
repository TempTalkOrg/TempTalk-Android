package com.difft.android.chat.ui

import com.difft.android.chat.message.ReadReceiptDispatcher
import com.difft.android.chat.message.SenderReadReceiptAcc
import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test














class ReadReceiptAccumulationTest {

    private fun msg(from: String, ts: Long, sysTs: Long = ts): MessageModel =
        MessageModel().apply {
            fromWho = from
            timeStamp = ts
            systemShowTimestamp = sysTs
            notifySequenceId = sysTs
            sequenceId = sysTs
        }

    
    private fun accumulate(messages: List<MessageModel>): Pair<HashMap<String, SenderReadReceiptAcc>, MessageModel?> {
        val perSender = HashMap<String, SenderReadReceiptAcc>()
        var globalMax: MessageModel? = null
        messages.forEach { m ->
            ReadReceiptDispatcher.accumulateReadReceipt(perSender, m)
            val cur = globalMax
            if (cur == null || m.systemShowTimestamp > cur.systemShowTimestamp) globalMax = m
        }
        return perSender to globalMax
    }

    
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

    
    @Test
    fun `global max is the latest message across all senders`() {
        val messages = listOf(msg("A", 100), msg("B", 500), msg("A", 300))
        val (_, globalMax) = accumulate(messages)
        val max = checkNotNull(globalMax)

        assertEquals(500L, max.systemShowTimestamp)
        assertEquals("B", max.fromWho)
    }

    
    @Test
    fun `empty range yields empty accumulator and null global max`() {
        val (perSender, globalMax) = accumulate(emptyList())
        assertTrue(perSender.isEmpty())
        assertEquals(null, globalMax)
    }

    
    @Test
    fun `timestamps over the cap split into multiple chunks covering the full set`() {
        val cap = ReadReceiptDispatcher.MAX_TIMESTAMPS_PER_RECEIPT
        val total = cap + 50
        val messages = (1..total).map { msg("A", it.toLong()) }
        val (perSender, _) = accumulate(messages)

        val chunks = perSender["A"]!!.timeStamps.chunked(cap)
        assertEquals(2, chunks.size)                       
        assertEquals(cap, chunks[0].size)
        assertEquals(50, chunks[1].size)
        chunks.forEach { assertTrue(it.size <= cap) }
        
        assertEquals((1L..total.toLong()).toList(), chunks.flatten())
    }

    
    @Test
    fun `timestamps exactly at the cap stay a single chunk`() {
        val cap = ReadReceiptDispatcher.MAX_TIMESTAMPS_PER_RECEIPT
        val messages = (1..cap).map { msg("A", it.toLong()) }
        val (perSender, _) = accumulate(messages)

        val chunks = perSender["A"]!!.timeStamps.chunked(cap)
        assertEquals(1, chunks.size)
        assertEquals(cap, chunks[0].size)
    }

    
    
    
    @Test
    fun `large group plans no per-sender receipt jobs`() {
        val (perSender, _) = accumulate(listOf(msg("A", 100), msg("B", 200)))

        val plans = ReadReceiptDispatcher.planReadReceiptJobs(perSender, isLargeGroup = true)
        assertTrue(plans.isEmpty())
    }

    
    
    @Test
    fun `small group plans one job per sender carrying its timestamps and max`() {
        val (perSender, _) = accumulate(
            listOf(msg("A", 100), msg("B", 150), msg("A", 200))
        )

        val plans = ReadReceiptDispatcher.planReadReceiptJobs(perSender, isLargeGroup = false)

        assertEquals(2, plans.size)
        val byRecipient = plans.associateBy { it.recipientId }
        assertEquals(listOf(100L, 200L), byRecipient["A"]!!.timeStamps)
        assertEquals(200L, byRecipient["A"]!!.maxMessage.systemShowTimestamp)
        assertEquals(listOf(150L), byRecipient["B"]!!.timeStamps)
        assertEquals(150L, byRecipient["B"]!!.maxMessage.systemShowTimestamp)
    }

    
    
    @Test
    fun `plan splits an over-cap sender into multiple jobs sharing one read position`() {
        val cap = ReadReceiptDispatcher.MAX_TIMESTAMPS_PER_RECEIPT
        val total = cap + 50
        val (perSender, _) = accumulate((1..total).map { msg("A", it.toLong()) })

        val plans = ReadReceiptDispatcher.planReadReceiptJobs(perSender, isLargeGroup = false)

        assertEquals(2, plans.size)                                   
        plans.forEach { assertTrue(it.timeStamps.size <= cap) }
        
        assertEquals(1, plans.map { it.maxMessage }.distinct().size)
        
        assertEquals((1L..total.toLong()).toList(), plans.flatMap { it.timeStamps })
    }
}
