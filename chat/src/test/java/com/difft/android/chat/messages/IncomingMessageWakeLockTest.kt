package com.difft.android.chat.messages

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** #1376: [IncomingMessageWakeLock] holds the CPU while queued envelopes wait for their batch. */
class IncomingMessageWakeLockTest {

    private val acquired = mutableListOf<Long>()
    private lateinit var lock: IncomingMessageWakeLock

    @Before
    fun setUp() {
        lock = IncomingMessageWakeLock(mockk<Context>(relaxed = true))
        lock.acquire = { acquired += it }
    }

    @Test
    fun `queued envelope holds the lock until its batch is processed then shortens to the grace`() {
        val seq = lock.onQueued()
        lock.onProcessed(seq)

        assertEquals(listOf(IncomingMessageWakeLock.HOLD_TIMEOUT_MS, IncomingMessageWakeLock.GRACE_MS), acquired)
    }

    @Test
    fun `an envelope queued during processing keeps the full hold`() {
        val first = lock.onQueued()
        lock.onQueued()
        lock.onProcessed(first)

        assertEquals(List(2) { IncomingMessageWakeLock.HOLD_TIMEOUT_MS }, acquired)
    }

    @Test
    fun `batch start re-arms the full hold so a long batch keeps the CPU`() {
        val seq = lock.onQueued()
        lock.onBatchStart()
        lock.onProcessed(seq)

        assertEquals(
            listOf(IncomingMessageWakeLock.HOLD_TIMEOUT_MS, IncomingMessageWakeLock.HOLD_TIMEOUT_MS, IncomingMessageWakeLock.GRACE_MS),
            acquired
        )
    }

    @Test
    fun `a later batch settles an envelope that never reached a batch`() {
        lock.onQueued() // dropped emission
        val second = lock.onQueued()
        lock.onProcessed(second)

        assertEquals(IncomingMessageWakeLock.GRACE_MS, acquired.last())
    }
}
