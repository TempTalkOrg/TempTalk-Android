package com.difft.android.chat.messages

import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.mockk
import org.difft.app.database.WCDB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit










class FailedMessageProcessorTest {

    private lateinit var processor: FailedMessageProcessor

    @Before
    fun setUp() {
        processor = FailedMessageProcessor(
            envelopToMessageProcessor = mockk(relaxed = true),
            wcdb = mockk<WCDB>(relaxed = true),
            messagingGate = TestMessagingPause().open().gate,
        )
    }

    

    @Test
    fun `MAX_RETRIES equals 5`() {
        assertEquals(5, FailedMessageProcessor.MAX_RETRIES)
    }

    @Test
    fun `MAX_PER_TICK equals 50`() {
        assertEquals(50, FailedMessageProcessor.MAX_PER_TICK)
    }

    @Test
    fun `TTL_MILLIS equals 3 days`() {
        assertEquals(TimeUnit.DAYS.toMillis(3), FailedMessageProcessor.TTL_MILLIS)
    }

    @Test
    fun `BACKOFF_TABLE has 5 entries matching design (1s, 5s, 30s, 5m, 30m)`() {
        val expected = longArrayOf(
            TimeUnit.SECONDS.toMillis(1),
            TimeUnit.SECONDS.toMillis(5),
            TimeUnit.SECONDS.toMillis(30),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(30),
        )
        assertEquals(expected.size, FailedMessageProcessor.BACKOFF_TABLE.size)
        for (i in expected.indices) {
            assertEquals("BACKOFF_TABLE[$i]", expected[i], FailedMessageProcessor.BACKOFF_TABLE[i])
        }
    }

    

    @Test
    fun `backoffMillis(0) is 1s plus or minus 20 percent jitter`() {
        val base = TimeUnit.SECONDS.toMillis(1)
        val tolerance = base / 5L
        repeat(100) {
            val v = processor.backoffMillis(0)
            assertTrue(
                "backoffMillis(0)=$v should be in [${base - tolerance}, ${base + tolerance}]",
                v in (base - tolerance)..(base + tolerance)
            )
        }
    }

    @Test
    fun `backoffMillis(4) is 30 min plus or minus 20 percent jitter`() {
        val base = TimeUnit.MINUTES.toMillis(30)
        val tolerance = base / 5L
        repeat(100) {
            val v = processor.backoffMillis(4)
            assertTrue(
                "backoffMillis(4)=$v should be in [${base - tolerance}, ${base + tolerance}]",
                v in (base - tolerance)..(base + tolerance)
            )
        }
    }

    @Test
    fun `backoffMillis clamps retryCount above MAX to last bucket (defensive)`() {
        
        
        val v = processor.backoffMillis(99)
        val base = TimeUnit.MINUTES.toMillis(30)
        val tolerance = base / 5L
        assertTrue(v in (base - tolerance)..(base + tolerance))
    }

    @Test
    fun `backoffMillis jitter is actually random (not constant)`() {
        
        
        
        val samples = (0..50).map { processor.backoffMillis(2) }.toSet()
        assertNotEquals("Expected jitter variance across samples", 1, samples.size)
    }
}
